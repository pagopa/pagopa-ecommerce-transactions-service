package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.*;
import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestsInfoRepository;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.PaymentNoticeInfoDto;
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.NodoOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class TransactionActivateHandler
        implements
        CommandHandler<TransactionActivateCommand, Mono<Tuple3<Mono<TransactionActivatedEvent>, Mono<TransactionActivationRequestedEvent>, SessionDataDto>>> {

    private final PaymentRequestsInfoRepository paymentRequestsInfoRepository;

    private final TransactionsEventStoreRepository<TransactionActivatedData> transactionEventActivatedStoreRepository;

    private final TransactionsEventStoreRepository<TransactionActivationRequestedData> transactionEventActivationRequestedStoreRepository;

    private final EcommerceSessionsClient ecommerceSessionsClient;

    private final NodoOperations nodoOperations;

    private final QueueAsyncClient transactionActivatedQueueAsyncClient;

    private final Integer paymentTokenTimeout;

    @Value("${nodo.parallelRequests}")
    private int nodoParallelRequests;

    @Autowired
    public TransactionActivateHandler(
            PaymentRequestsInfoRepository paymentRequestsInfoRepository,
            TransactionsEventStoreRepository<TransactionActivatedData> transactionEventActivatedStoreRepository,
            TransactionsEventStoreRepository<TransactionActivationRequestedData> transactionEventActivationRequestedStoreRepository,
            EcommerceSessionsClient ecommerceSessionsClient,
            NodoOperations nodoOperations,
            @Qualifier("transactionActivatedQueueAsyncClient") QueueAsyncClient transactionActivatedQueueAsyncClient,
            @Value("${payment.token.validity}") Integer paymentTokenTimeout
    ) {
        this.paymentRequestsInfoRepository = paymentRequestsInfoRepository;
        this.transactionEventActivatedStoreRepository = transactionEventActivatedStoreRepository;
        this.transactionEventActivationRequestedStoreRepository = transactionEventActivationRequestedStoreRepository;
        this.ecommerceSessionsClient = ecommerceSessionsClient;
        this.nodoOperations = nodoOperations;
        this.paymentTokenTimeout = paymentTokenTimeout;
        this.transactionActivatedQueueAsyncClient = transactionActivatedQueueAsyncClient;
    }

    public Mono<Tuple3<Mono<TransactionActivatedEvent>, Mono<TransactionActivationRequestedEvent>, SessionDataDto>> handle(
                                                                                                                           TransactionActivateCommand command
    ) {

        final NewTransactionRequestDto newTransactionRequestDto = command.getData();
        // TODO correggere qui, prendere il payment context code per ogni singolo
        // pagamento
        final String paymentContextCode = newTransactionRequestDto.getPaymentNotices().get(0).getPaymentContextCode();
        final int totalPaymentNotices = newTransactionRequestDto.getPaymentNotices().size();
        final AtomicInteger processedPaymentNoticeCount = new AtomicInteger(0);
        log.info("Nodo parallelism: [{}]", nodoParallelRequests);
        return Mono.defer(
                () -> Flux.fromIterable(newTransactionRequestDto.getPaymentNotices())
                        .parallel(nodoParallelRequests)
                        .runOn(Schedulers.parallel())
                        .flatMap(
                                paymentNotice -> {
                                    log.debug(
                                            "Start processing of payment request: [{}/{}]",
                                            processedPaymentNoticeCount.addAndGet(1),
                                            totalPaymentNotices
                                    );
                                    return Mono.just(
                                            Tuples.of(
                                                    paymentNotice,
                                                    getPaymentRequestInfoFromCache(new RptId(paymentNotice.getRptId()))
                                            )
                                    );
                                }
                        ).flatMap(
                                cacheResult -> {
                                    PaymentNoticeInfoDto paymentNotice = cacheResult.getT1();
                                    PaymentRequestInfo partialPaymentRequestInfo = cacheResult.getT2();
                                    Boolean isValidPaymentToken = isValidPaymentToken(
                                            partialPaymentRequestInfo.paymentToken()
                                    );
                                    return Boolean.TRUE.equals(isValidPaymentToken)
                                            ? Mono.just(partialPaymentRequestInfo)
                                                    .doOnSuccess(
                                                            p -> log.info(
                                                                    "PaymentRequestInfo cache hit for {} with valid paymentToken {}",
                                                                    p.id(),
                                                                    p.paymentToken()
                                                            )
                                                    )
                                            : nodoOperations
                                                    .activatePaymentRequest(
                                                            partialPaymentRequestInfo,
                                                            paymentNotice.getPaymentContextCode(),
                                                            paymentNotice.getAmount()
                                                    )
                                                    .doOnSuccess(
                                                            p -> log.info(
                                                                    "Nodo activation for {} with paymentToken {}",
                                                                    p.id(),
                                                                    p.paymentToken()
                                                            )
                                                    );
                                }
                        )
                        .doOnNext(
                                paymentRequestInfo -> {
                                    log.info(
                                            "Cache Nodo activation info for {} with paymentToken {}",
                                            paymentRequestInfo.id(),
                                            paymentRequestInfo.paymentToken()
                                    );
                                    paymentRequestsInfoRepository.save(paymentRequestInfo);
                                }
                        )
                        .sequential()
                        .collectList()
                        .flatMap(paymentRequestInfos -> {
                            processedPaymentNoticeCount.set(0);
                            // TODO post adeguamento modulo sessions passare l'array di payment info, se
                            // necessario
                            String transactionId = UUID.randomUUID().toString();
                            PaymentRequestInfo paymentRequestInfo = paymentRequestInfos.get(0);
                            SessionRequestDto sessionRequest = new SessionRequestDto()
                                    .email(newTransactionRequestDto.getEmail())
                                    .rptId(paymentRequestInfo.id().value())
                                    .transactionId(transactionId)
                                    .paymentToken(paymentRequestInfo.paymentToken());

                            return ecommerceSessionsClient
                                    .createSessionToken(sessionRequest)
                                    .map(sessionData -> Tuples.of(sessionData, paymentRequestInfos));
                        }).flatMap(
                                args -> {
                                    SessionDataDto sessionDataDto = args.getT1();
                                    List<PaymentRequestInfo> paymentRequestsInfo = args.getT2();
                                    return areAllValidPaymentTokens(paymentRequestsInfo)
                                            ? Mono.just(
                                                    Tuples.of(
                                                            newTransactionActivatedEvent(
                                                                    paymentRequestsInfo,
                                                                    sessionDataDto.getTransactionId(),
                                                                    sessionDataDto.getEmail()
                                                            ),
                                                            Mono.empty(),
                                                            sessionDataDto
                                                    )
                                            )
                                            : Mono.just(
                                                    Tuples.of(
                                                            Mono.empty(),
                                                            newTransactionActivationRequestedEvent(
                                                                    paymentRequestsInfo,
                                                                    sessionDataDto.getTransactionId(),
                                                                    sessionDataDto.getEmail(),
                                                                    paymentContextCode
                                                            ),
                                                            sessionDataDto
                                                    )
                                            );
                                }
                        )

        );
    }

    private PaymentRequestInfo getPaymentRequestInfoFromCache(RptId rptId) {
        Optional<PaymentRequestInfo> paymentInfofromCache = paymentRequestsInfoRepository.findById(rptId);
        log.info("PaymentRequestInfo cache hit for {}: {}", rptId, paymentInfofromCache.isPresent());
        return paymentInfofromCache.orElseGet(
                () -> new PaymentRequestInfo(
                        rptId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null

                )
        );
    }

    private boolean isValidPaymentToken(String paymentToken) {
        return paymentToken != null && !paymentToken.isBlank();
    }

    private boolean areAllValidPaymentTokens(List<PaymentRequestInfo> paymentRequestInfos) {
        int payments = paymentRequestInfos.size();
        int validPayments = paymentRequestInfos.stream()
                .filter(paymentRequestInfo -> isValidPaymentToken(paymentRequestInfo.paymentToken())).toList().size();
        log.info("Input payments: [{}], valid payments: [{}]", payments, validPayments);
        return payments == validPayments;
    }

    private Mono<TransactionActivationRequestedEvent> newTransactionActivationRequestedEvent(
                                                                                             List<PaymentRequestInfo> paymentRequestsInfo,
                                                                                             String transactionId,
                                                                                             String email,
                                                                                             String paymentContextCode
    ) {
        // TODO qui il payment context code deve essere UNO per ogni pagamento, quindi
        // va modificata qui l'oggetto data!
        TransactionActivationRequestedData data = new TransactionActivationRequestedData();
        data.setEmail(email);
        List<NoticeCode> noticeCodes = toNoticeCodeList(paymentRequestsInfo);
        data.setNoticeCodes(noticeCodes);
        data.setPaymentContextCode(paymentContextCode);
        TransactionActivationRequestedEvent transactionActivationRequestedEvent = new TransactionActivationRequestedEvent(
                transactionId,
                noticeCodes,
                data
        );

        log.info(
                "Generated event TRANSACTION_ACTIVATION_REQUESTED_EVENT for rptId {} and transactionId {}",
                String.join(
                        ",",
                        transactionActivationRequestedEvent.getNoticeCodes().stream().map(NoticeCode::getRptId).toList()
                ),
                transactionId
        );

        return transactionEventActivationRequestedStoreRepository.save(
                transactionActivationRequestedEvent
        );
    }

    private Mono<TransactionActivatedEvent> newTransactionActivatedEvent(
                                                                         List<PaymentRequestInfo> paymentRequestsInfo,
                                                                         String transactionId,
                                                                         String email
    ) {

        TransactionActivatedData data = new TransactionActivatedData();
        data.setEmail(email);
        List<NoticeCode> noticeCodes = toNoticeCodeList(paymentRequestsInfo);
        data.setNoticeCodes(noticeCodes);

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId,
                noticeCodes,
                data
        );

        return transactionEventActivatedStoreRepository
                .save(transactionActivatedEvent)
                .then(
                        transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                                BinaryData.fromObject(transactionActivatedEvent),
                                Duration.ofSeconds(paymentTokenTimeout),
                                null
                        )
                )
                .then(Mono.just(transactionActivatedEvent))
                .doOnError(
                        exception -> log.error(
                                "Error to generate event TRANSACTION_ACTIVATED_EVENT for rptIds {} and transactionId {} - error {}",
                                String.join(
                                        ",",
                                        transactionActivatedEvent.getNoticeCodes().stream().map(NoticeCode::getRptId)
                                                .toList()
                                ),
                                transactionActivatedEvent.getTransactionId(),
                                exception.getMessage()
                        )
                )
                .doOnNext(
                        event -> log.info(
                                "Generated event TRANSACTION_ACTIVATED_EVENT for rptIds {} and transactionId {}",
                                String.join(",", event.getNoticeCodes().stream().map(NoticeCode::getRptId).toList()),
                                event.getTransactionId()
                        )
                );
    }

    private List<NoticeCode> toNoticeCodeList(List<PaymentRequestInfo> paymentRequestsInfo) {
        return paymentRequestsInfo.stream().map(
                paymentRequestInfo -> new NoticeCode(
                        paymentRequestInfo.paymentToken(),
                        paymentRequestInfo.id().value(),
                        paymentRequestInfo.description(),
                        paymentRequestInfo.amount()
                )
        ).toList();
    }
}
