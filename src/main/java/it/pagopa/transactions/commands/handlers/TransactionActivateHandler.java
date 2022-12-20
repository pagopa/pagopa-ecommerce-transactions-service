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
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.NodoOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

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
        final RptId rptId = command.getRptId();
        final NewTransactionRequestDto newTransactionRequestDto = command.getData();
        final String paymentContextCode = newTransactionRequestDto.getPaymentNotices().get(0).getPaymentContextCode();

        return getPaymentRequestInfoFromCache(rptId)
                .doOnNext(
                        paymentRequestInfoFromCache -> log.info(
                                "PaymentRequestInfo cache hit for {}: {}",
                                rptId,
                                paymentRequestInfoFromCache != null
                        )
                )
                .switchIfEmpty(
                        Mono.defer(
                                () -> Mono.just(
                                        new PaymentRequestInfo(
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
                                )
                                        .doOnSuccess(x -> log.info("PaymentRequestInfo cache miss for {}", rptId))
                        )
                )
                .flatMap(
                        partialPaymentRequestInfo -> {
                            final Boolean isValidPaymentToken = isValidPaymentToken(
                                    partialPaymentRequestInfo.paymentToken()
                            );
                            return Boolean.TRUE.equals(isValidPaymentToken)
                                    ? Mono.just(partialPaymentRequestInfo)
                                            .doOnSuccess(
                                                    p -> log.info(
                                                            "PaymentRequestInfo cache hit for {} with valid paymentToken {}",
                                                            rptId,
                                                            p.paymentToken()
                                                    )
                                            )
                                    : nodoOperations
                                            .activatePaymentRequest(partialPaymentRequestInfo, newTransactionRequestDto)
                                            .doOnSuccess(
                                                    p -> log.info(
                                                            "Nodo activation for {} with paymentToken {}",
                                                            rptId,
                                                            p.paymentToken()
                                                    )
                                            );
                        }
                )
                .doOnNext(
                        paymentRequestInfo -> {
                            log.info(
                                    "Cache Nodo activation info for {} with paymentToken {}",
                                    rptId,
                                    paymentRequestInfo.paymentToken()
                            );
                            paymentRequestsInfoRepository.save(paymentRequestInfo);
                        }
                )
                .flatMap(
                        paymentRequestInfo -> {
                            final String transactionId = UUID.randomUUID().toString();
                            final SessionRequestDto sessionRequest = new SessionRequestDto()
                                    .email(newTransactionRequestDto.getEmail())
                                    .rptId(paymentRequestInfo.id().value())
                                    .transactionId(transactionId)
                                    .paymentToken(paymentRequestInfo.paymentToken());

                            return ecommerceSessionsClient
                                    .createSessionToken(sessionRequest)
                                    .map(sessionData -> Tuples.of(sessionData, paymentRequestInfo));
                        }
                )
                .flatMap(
                        args -> {
                            final SessionDataDto sessionDataDto = args.getT1();
                            final PaymentRequestInfo paymentRequestInfo = args.getT2();
                            final String paymentToken = paymentRequestInfo.paymentToken();
                            return isValidPaymentToken(paymentToken)
                                    ? Mono.just(
                                            Tuples.of(
                                                    newTransactionActivatedEvent(
                                                            paymentRequestInfo.amount(),
                                                            paymentRequestInfo.description(),
                                                            sessionDataDto.getEmail(),
                                                            sessionDataDto.getTransactionId(),
                                                            sessionDataDto.getRptId(),
                                                            paymentToken
                                                    ),
                                                    Mono.empty(),
                                                    sessionDataDto
                                            )
                                    )
                                    : Mono.just(
                                            Tuples.of(
                                                    Mono.empty(),
                                                    newTransactionActivationRequestedEvent(
                                                            paymentRequestInfo.amount(),
                                                            paymentRequestInfo.description(),
                                                            sessionDataDto.getEmail(),
                                                            sessionDataDto.getTransactionId(),
                                                            sessionDataDto.getRptId(),
                                                            paymentContextCode
                                                    ),
                                                    sessionDataDto
                                            )
                                    );
                        }
                );
    }

    private Mono<PaymentRequestInfo> getPaymentRequestInfoFromCache(RptId rptId) {

        return paymentRequestsInfoRepository.findById(rptId).map(Mono::just).orElseGet(Mono::empty);
    }

    private boolean isValidPaymentToken(String paymentToken) {
        return paymentToken != null && !paymentToken.isBlank();
    }

    private Mono<TransactionActivationRequestedEvent> newTransactionActivationRequestedEvent(
                                                                                             Integer amount,
                                                                                             String description,
                                                                                             String email,
                                                                                             String transactionId,
                                                                                             String rptId,
                                                                                             String paymentContextCode
    ) {

        TransactionActivationRequestedData data = new TransactionActivationRequestedData();
        data.setEmail(email);
        NoticeCode noticeCode = new NoticeCode(null, rptId, description, amount);
        data.setNoticeCodes(Arrays.asList(noticeCode));
        data.setPaymentContextCode(paymentContextCode);
        TransactionActivationRequestedEvent transactionActivationRequestedEvent = new TransactionActivationRequestedEvent(
                transactionId,
                Arrays.asList(noticeCode),
                data
        );

        log.info(
                "Generated event TRANSACTION_ACTIVATION_REQUESTED_EVENT for rptId {} and transactionId {}",
                rptId,
                transactionId
        );

        return transactionEventActivationRequestedStoreRepository.save(
                transactionActivationRequestedEvent
        );
    }

    private Mono<TransactionActivatedEvent> newTransactionActivatedEvent(
                                                                         Integer amount,
                                                                         String description,
                                                                         String email,
                                                                         String transactionId,
                                                                         String rptId,
                                                                         String paymentToken
    ) {

        TransactionActivatedData data = new TransactionActivatedData();
        data.setEmail(email);
        NoticeCode noticeCode = new NoticeCode(paymentToken, rptId, description, amount);
        data.setNoticeCodes(Arrays.asList(noticeCode));

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId,
                Arrays.asList(noticeCode),
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
                                "Error to generate event TRANSACTION_ACTIVATED_EVENT for rptId {} and transactionId {} - error {}",
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
                                "Generated event TRANSACTION_ACTIVATED_EVENT for rptId {} and transactionId {}",
                                String.join(",", event.getNoticeCodes().stream().map(NoticeCode::getRptId).toList()),
                                event.getTransactionId()
                        )
                );
    }
}
