package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.*;
import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestsInfoRepository;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.PaymentNoticeInfoDto;
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
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class TransactionActivateHandler
        implements
        CommandHandler<TransactionActivateCommand, Mono<Tuple2<Mono<TransactionActivatedEvent>, Mono<TransactionActivationRequestedEvent>>>> {

    private final PaymentRequestsInfoRepository paymentRequestsInfoRepository;

    private final TransactionsEventStoreRepository<TransactionActivatedData> transactionEventActivatedStoreRepository;

    private final TransactionsEventStoreRepository<TransactionActivationRequestedData> transactionEventActivationRequestedStoreRepository;

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
            NodoOperations nodoOperations,
            @Qualifier("transactionActivatedQueueAsyncClient") QueueAsyncClient transactionActivatedQueueAsyncClient,
            @Value("${payment.token.validity}") Integer paymentTokenTimeout
    ) {
        this.paymentRequestsInfoRepository = paymentRequestsInfoRepository;
        this.transactionEventActivatedStoreRepository = transactionEventActivatedStoreRepository;
        this.transactionEventActivationRequestedStoreRepository = transactionEventActivationRequestedStoreRepository;
        this.nodoOperations = nodoOperations;
        this.paymentTokenTimeout = paymentTokenTimeout;
        this.transactionActivatedQueueAsyncClient = transactionActivatedQueueAsyncClient;
    }

    public Mono<Tuple2<Mono<TransactionActivatedEvent>, Mono<TransactionActivationRequestedEvent>>> handle(
                                                                                                           TransactionActivateCommand command
    ) {
        final String transactionId = command.getTransactionId().value().toString();
        final NewTransactionRequestDto newTransactionRequestDto = command.getData();
        final List<PaymentNoticeInfoDto> paymentNotices = newTransactionRequestDto.getPaymentNotices();
        final boolean multiplePaymentNotices = paymentNotices.size() > 1;
        final String paymentContextCode = Optional.ofNullable(paymentNotices.get(0).getPaymentContextCode())
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", ""));

        log.info(
                "Nodo parallel processed requests : [{}]. Multiple payment notices: [{}]",
                nodoParallelRequests,
                multiplePaymentNotices
        );
        return Mono.defer(
                () -> Flux.fromIterable(paymentNotices)
                        .parallel(nodoParallelRequests)
                        .runOn(Schedulers.parallel())
                        .flatMap(
                                paymentNotice -> Mono.just(
                                        Tuples.of(
                                                paymentNotice,
                                                getPaymentRequestInfoFromCache(new RptId(paymentNotice.getRptId()))
                                        )
                                )
                        ).flatMap(
                                cacheResult -> {
                                    /* @formatter:off
                                     *
                                     * There are three possible cases here:
                                     *  - Cache hit with payment token => Return the cached value
                                     *  - Cache hit without payment token => Activate payment
                                     *  - Cache miss => Activate payment
                                     *
                                     * @formatter:on
                                     */

                                    PaymentNoticeInfoDto paymentNotice = cacheResult.getT1();
                                    Optional<PaymentRequestInfo> partialPaymentRequestInfo = cacheResult.getT2();

                                    return partialPaymentRequestInfo
                                            .filter(requestInfo -> isValidPaymentToken(requestInfo.paymentToken()))
                                            .map(
                                                    requestInfo -> Mono.just(requestInfo)
                                                            .doOnSuccess(
                                                                    p -> log.info(
                                                                            "PaymentRequestInfo cache hit for {} with valid paymentToken {}",
                                                                            p.id(),
                                                                            p.paymentToken()
                                                                    )
                                                            )
                                            )
                                            .orElseGet(
                                                    () -> nodoOperations
                                                            .activatePaymentRequest(
                                                                    new RptId(paymentNotice.getRptId()),
                                                                    partialPaymentRequestInfo,
                                                                    paymentContextCode,
                                                                    paymentNotice.getAmount(),
                                                                    multiplePaymentNotices,
                                                                    transactionId
                                                            )
                                                            .doOnSuccess(
                                                                    p -> log.info(
                                                                            "Nodo activation for {} with paymentToken {}",
                                                                            p.id(),
                                                                            p.paymentToken()
                                                                    )
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
                        .flatMap(
                                paymentRequestsInfo -> shouldGenerateTransactionActivatedEvent(paymentRequestsInfo)
                                        ? Mono.just(
                                                Tuples.of(
                                                        newTransactionActivatedEvent(
                                                                paymentRequestsInfo,
                                                                command.getTransactionId().value().toString(),
                                                                command.getData().getEmail(),
                                                                command.getClientId()
                                                        ),
                                                        Mono.empty()
                                                )
                                        )
                                        : Mono.just(
                                                Tuples.of(
                                                        Mono.empty(),
                                                        newTransactionActivationRequestedEvent(
                                                                paymentRequestsInfo,
                                                                command.getTransactionId().value().toString(),
                                                                command.getData().getEmail(),
                                                                paymentContextCode,
                                                                command.getClientId()
                                                        )
                                                )
                                        )
                        )

        );
    }

    private Optional<PaymentRequestInfo> getPaymentRequestInfoFromCache(RptId rptId) {
        Optional<PaymentRequestInfo> paymentInfofromCache = paymentRequestsInfoRepository.findById(rptId);
        log.info("PaymentRequestInfo cache hit for {}: {}", rptId, paymentInfofromCache.isPresent());
        return paymentInfofromCache;
    }

    private boolean isValidPaymentToken(String paymentToken) {
        return paymentToken != null && !paymentToken.isBlank();
    }

    private boolean shouldGenerateTransactionActivatedEvent(List<PaymentRequestInfo> paymentRequestsInfo) {
        int paymentRequestInfoSize = paymentRequestsInfo.size();
        boolean generateTransactionActivatedEvent = true;
        /*
         * When input transaction contains multiple payment notices (cart request) then
         * communication with Nodo component will be actuated only with the
         * "new primitive" (activatePaymentNotice) so a transactionActivatedEvent is
         * generated. If only one payment notice is contained into request then the
         * generated event will be one of
         * transactionActivated/transactionActivationRequested based on the paymentToken
         * presence
         */
        if (paymentRequestInfoSize == 1) {
            generateTransactionActivatedEvent = isValidPaymentToken(paymentRequestsInfo.get(0).paymentToken());
        }
        log.info(
                "Input payment notices: [{}] generate new transaction activated event: [{}]",
                paymentRequestInfoSize,
                generateTransactionActivatedEvent
        );
        return generateTransactionActivatedEvent;
    }

    private Mono<TransactionActivationRequestedEvent> newTransactionActivationRequestedEvent(
                                                                                             List<PaymentRequestInfo> paymentRequestsInfo,
                                                                                             String transactionId,
                                                                                             String email,
                                                                                             String paymentContextCode,
                                                                                             Transaction.ClientId clientId
    ) {
        PaymentRequestInfo paymentRequestInfo = paymentRequestsInfo.get(0);
        List<PaymentNotice> paymentNotices = List.of(
                new PaymentNotice(
                        null,
                        paymentRequestInfo.id().value(),
                        paymentRequestInfo.description(),
                        paymentRequestInfo.amount(),
                        paymentContextCode
                )
        );
        TransactionActivationRequestedData data = new TransactionActivationRequestedData(
                paymentNotices,
                email,
                null,
                null,
                clientId
        );
        TransactionActivationRequestedEvent transactionActivationRequestedEvent = new TransactionActivationRequestedEvent(
                transactionId,
                data
        );

        log.info(
                "Generated event TRANSACTION_ACTIVATION_REQUESTED_EVENT for transactionId {}",
                transactionId
        );

        return transactionEventActivationRequestedStoreRepository.save(
                transactionActivationRequestedEvent
        );
    }

    private Mono<TransactionActivatedEvent> newTransactionActivatedEvent(
                                                                         List<PaymentRequestInfo> paymentRequestsInfo,
                                                                         String transactionId,
                                                                         String email,
                                                                         Transaction.ClientId clientId
    ) {
        List<PaymentNotice> paymentNotices = toPaymentNoticeList(paymentRequestsInfo);
        TransactionActivatedData data = new TransactionActivatedData(
                email,
                paymentNotices,
                null,
                null,
                clientId
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId,
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
                                "Error to generate event TRANSACTION_ACTIVATED_EVENT for transactionId {} - error {}",
                                transactionActivatedEvent.getTransactionId(),
                                exception.getMessage()
                        )
                )
                .doOnNext(
                        event -> log.info(
                                "Generated event TRANSACTION_ACTIVATED_EVENT for transactionId {}",
                                event.getTransactionId()
                        )
                );
    }

    private List<PaymentNotice> toPaymentNoticeList(List<PaymentRequestInfo> paymentRequestsInfo) {
        return paymentRequestsInfo.stream().map(
                paymentRequestInfo -> new PaymentNotice(
                        paymentRequestInfo.paymentToken(),
                        paymentRequestInfo.id().value(),
                        paymentRequestInfo.description(),
                        paymentRequestInfo.amount(),
                        null
                )
        ).toList();
    }
}
