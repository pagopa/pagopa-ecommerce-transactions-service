package it.pagopa.transactions.commands.handlers;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.IdempotencyKey;
import it.pagopa.ecommerce.commons.domain.v1.RptId;
import it.pagopa.ecommerce.commons.domain.v1.TransactionId;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.PaymentNoticeInfoDto;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.JwtTokenUtils;
import it.pagopa.transactions.utils.NodoOperations;
import it.pagopa.transactions.utils.OpenTelemetryUtils;
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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class TransactionActivateHandler
        implements
        CommandHandler<TransactionActivateCommand, Mono<Tuple2<Mono<TransactionActivatedEvent>, String>>> {

    public static final int TRANSFER_LIST_MAX_SIZE = 5;
    private final PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper;

    private final TransactionsEventStoreRepository<TransactionActivatedData> transactionEventActivatedStoreRepository;

    private final NodoOperations nodoOperations;

    private final QueueAsyncClient transactionActivatedQueueAsyncClient;

    private final Integer paymentTokenTimeout;

    private final JwtTokenUtils jwtTokenUtils;

    private final ConfidentialMailUtils confidentialMailUtils;

    private final int transientQueuesTTLSeconds;

    private final int nodoParallelRequests;

    private final TracingUtils tracingUtils;

    private final OpenTelemetryUtils openTelemetryUtils;

    @Autowired
    public TransactionActivateHandler(
            PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper,
            TransactionsEventStoreRepository<TransactionActivatedData> transactionEventActivatedStoreRepository,
            NodoOperations nodoOperations,
            JwtTokenUtils jwtTokenUtils,
            @Qualifier("transactionActivatedQueueAsyncClient") QueueAsyncClient transactionActivatedQueueAsyncClient,
            @Value("${payment.token.validity}") Integer paymentTokenTimeout,
            ConfidentialMailUtils confidentialMailUtils,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") int transientQueuesTTLSeconds,
            @Value("${nodo.parallelRequests}") int nodoParallelRequests,
            TracingUtils tracingUtils,
            OpenTelemetryUtils openTelemetryUtils
    ) {
        this.paymentRequestInfoRedisTemplateWrapper = paymentRequestInfoRedisTemplateWrapper;
        this.transactionEventActivatedStoreRepository = transactionEventActivatedStoreRepository;
        this.nodoOperations = nodoOperations;
        this.paymentTokenTimeout = paymentTokenTimeout;
        this.transactionActivatedQueueAsyncClient = transactionActivatedQueueAsyncClient;
        this.jwtTokenUtils = jwtTokenUtils;
        this.confidentialMailUtils = confidentialMailUtils;
        this.transientQueuesTTLSeconds = transientQueuesTTLSeconds;
        this.nodoParallelRequests = nodoParallelRequests;
        this.tracingUtils = tracingUtils;
        this.openTelemetryUtils = openTelemetryUtils;
    }

    public Mono<Tuple2<Mono<TransactionActivatedEvent>, String>> handle(
                                                                        TransactionActivateCommand command
    ) {
        final TransactionId transactionId = command.getTransactionId();
        final NewTransactionRequestDto newTransactionRequestDto = command.getData();
        final List<PaymentNoticeInfoDto> paymentNotices = newTransactionRequestDto.getPaymentNotices();
        final boolean multiplePaymentNotices = paymentNotices.size() > 1;
        log.info(
                "Nodo parallel processed requests : [{}]. Multiple payment notices: [{}]. Id cart: [{}]",
                nodoParallelRequests,
                multiplePaymentNotices,
                Optional.ofNullable(newTransactionRequestDto.getIdCart()).orElse("id cart not found")
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
                                paymentRequest -> {
                                    final PaymentNoticeInfoDto paymentNotice = paymentRequest.getT1();
                                    final Optional<PaymentRequestInfo> maybePaymentRequestInfo = paymentRequest
                                            .getT2();
                                    final String dueDate = maybePaymentRequestInfo.map(PaymentRequestInfo::dueDate)
                                            .orElse(null);
                                    return Mono.just(
                                            Tuples.of(
                                                    paymentNotice,
                                                    maybePaymentRequestInfo
                                                            .filter(
                                                                    requestInfo -> isValidIdempotencyKey(
                                                                            requestInfo.idempotencyKey()
                                                                    )
                                                            )
                                                            .orElseGet(
                                                                    () -> {
                                                                        PaymentRequestInfo paymentRequestWithOnlyIdempotencyKey = new PaymentRequestInfo(
                                                                                new RptId(
                                                                                        paymentNotice.getRptId()
                                                                                ),
                                                                                null,
                                                                                null,
                                                                                null,
                                                                                null,
                                                                                dueDate,
                                                                                null,
                                                                                null,
                                                                                new IdempotencyKey(
                                                                                        nodoOperations
                                                                                                .getEcommerceFiscalCode(),
                                                                                        nodoOperations
                                                                                                .generateRandomStringToIdempotencyKey()
                                                                                ),
                                                                                new ArrayList<>(TRANSFER_LIST_MAX_SIZE),
                                                                                null
                                                                        );
                                                                        paymentRequestInfoRedisTemplateWrapper
                                                                                .save(
                                                                                        paymentRequestWithOnlyIdempotencyKey
                                                                                );
                                                                        return paymentRequestWithOnlyIdempotencyKey;
                                                                    }
                                                            )
                                            )
                                    );
                                }
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

                                    final PaymentNoticeInfoDto paymentNotice = cacheResult.getT1();
                                    final PaymentRequestInfo partialPaymentRequestInfo = cacheResult.getT2();
                                    final IdempotencyKey idempotencyKey = partialPaymentRequestInfo.idempotencyKey();
                                    final RptId rptId = new RptId(paymentNotice.getRptId());

                                    return Optional.of(partialPaymentRequestInfo)
                                            .filter(requestInfo -> isValidPaymentToken(requestInfo.paymentToken()))
                                            .map(
                                                    requestInfo -> Mono.just(requestInfo)
                                                            .doOnSuccess(
                                                                    this::traceRepeatedActivation
                                                            )
                                            )
                                            .orElseGet(
                                                    () -> nodoOperations
                                                            .activatePaymentRequest(
                                                                    rptId,
                                                                    idempotencyKey,
                                                                    paymentNotice.getAmount(),
                                                                    transactionId.value(),
                                                                    paymentTokenTimeout,
                                                                    newTransactionRequestDto.getIdCart(),
                                                                    partialPaymentRequestInfo.dueDate()
                                                            )
                                                            .doOnSuccess(
                                                                    p -> {
                                                                        log.info(
                                                                                "Nodo activation for {} with paymentToken {}",
                                                                                p.id(),
                                                                                p.paymentToken()
                                                                        );
                                                                        paymentRequestInfoRedisTemplateWrapper.save(p);
                                                                    }
                                                            )
                                            );
                                }
                        )
                        .sequential()
                        .collectList()
                        .flatMap(
                                paymentRequestInfos -> jwtTokenUtils
                                        .generateToken(transactionId)
                                        .map(generatedToken -> Tuples.of(generatedToken, paymentRequestInfos))
                        ).flatMap(
                                args -> {
                                    String authToken = args.getT1();
                                    List<PaymentRequestInfo> paymentRequestsInfo = args.getT2();
                                    return Mono.just(
                                            Tuples.of(
                                                    newTransactionActivatedEvent(
                                                            paymentRequestsInfo,
                                                            transactionId.value(),
                                                            newTransactionRequestDto.getEmail(),
                                                            command.getClientId(),
                                                            newTransactionRequestDto.getIdCart(),
                                                            paymentTokenTimeout
                                                    ),
                                                    authToken
                                            )
                                    );
                                }
                        )
        );
    }

    private void traceRepeatedActivation(PaymentRequestInfo paymentRequestInfo) {
        String transactionActivationDateString = paymentRequestInfo.activationDate();
        String paymentToken = paymentRequestInfo.paymentToken();
        if (transactionActivationDateString != null && paymentToken != null) {
            ZonedDateTime transactionActivation = ZonedDateTime.parse(transactionActivationDateString);
            ZonedDateTime paymentTokenValidityEnd = transactionActivation
                    .plus(Duration.ofSeconds(paymentTokenTimeout));
            Duration paymentTokenValidityTimeLeft = Duration.between(ZonedDateTime.now(), paymentTokenValidityEnd);

            /*
             * Issue https://github.com/elastic/kibana/issues/123256 Span events attached to
             * the Span.currentSpan() are not visible into Transaction detail so here we
             * start a new span as workaround in order to make this event visible also
             * inside Transaction view
             */
            openTelemetryUtils.addSpanWithAttributes(
                    OpenTelemetryUtils.REPEATED_ACTIVATION_SPAN_NAME,
                    Attributes.of(
                            AttributeKey.stringKey(OpenTelemetryUtils.REPEATED_ACTIVATION_PAYMENT_TOKEN_ATTRIBUTE_KEY),
                            paymentToken,
                            AttributeKey.longKey(
                                    OpenTelemetryUtils.REPEATED_ACTIVATION_PAYMENT_TOKEN_LEFT_TIME_ATTRIBUTE_KEY
                            ),
                            paymentTokenValidityTimeLeft.getSeconds()
                    )
            );
            log.info(
                    "PaymentRequestInfo cache hit for {} with valid paymentToken {}. Validity left time: {}",
                    paymentRequestInfo.id(),
                    paymentRequestInfo.paymentToken(),
                    paymentTokenValidityTimeLeft
            );
        } else {
            log.error(
                    "Cannot trace repeated transaction activation for {} with payment token: {}, missing transaction activation date",
                    paymentRequestInfo.id(),
                    paymentRequestInfo.paymentToken()
            );
            openTelemetryUtils.addErrorSpanWithException(
                    "Transaction re-activated",
                    new IllegalArgumentException(
                            "Null transaction activation date or payment token for rptId %s in repeated activation"
                                    .formatted(paymentRequestInfo.id().toString())
                    )
            );
        }

    }

    private Optional<PaymentRequestInfo> getPaymentRequestInfoFromCache(RptId rptId) {
        Optional<PaymentRequestInfo> paymentInfofromCache = paymentRequestInfoRedisTemplateWrapper
                .findById(rptId.value());
        log.info("PaymentRequestInfo cache hit for {}: {}", rptId, paymentInfofromCache.isPresent());
        return paymentInfofromCache;
    }

    private boolean isValidPaymentToken(String paymentToken) {
        return paymentToken != null && !paymentToken.isBlank();
    }

    private boolean isValidIdempotencyKey(IdempotencyKey idempotencyKey) {
        return idempotencyKey != null && !idempotencyKey.rawValue().isBlank();
    }

    private Mono<TransactionActivatedEvent> newTransactionActivatedEvent(
                                                                         List<PaymentRequestInfo> paymentRequestsInfo,
                                                                         String transactionId,
                                                                         String email,
                                                                         Transaction.ClientId clientId,
                                                                         String idCart,
                                                                         Integer paymentTokenTimeout
    ) {
        List<PaymentNotice> paymentNotices = toPaymentNoticeList(paymentRequestsInfo);
        Mono<TransactionActivatedData> data = confidentialMailUtils.toConfidential(email).map(
                e -> new TransactionActivatedData(
                        e,
                        paymentNotices,
                        null,
                        null,
                        clientId,
                        idCart,
                        paymentTokenTimeout
                )
        );

        Mono<TransactionActivatedEvent> transactionActivatedEvent = data.map(
                d -> new TransactionActivatedEvent(
                        transactionId,
                        d
                )
        );

        return transactionActivatedEvent.flatMap(transactionEventActivatedStoreRepository::save)
                .flatMap(
                        e -> tracingUtils.traceMono(
                                this.getClass().getSimpleName(),
                                tracingInfo -> transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                                        new QueueEvent<>(e, tracingInfo),
                                        Duration.ofSeconds(paymentTokenTimeout),
                                        Duration.ofSeconds(transientQueuesTTLSeconds)
                                ).thenReturn(e)
                        )
                )
                .doOnError(
                        exception -> log.error(
                                "Error to generate event TRANSACTION_ACTIVATED_EVENT for transactionId {} - error {}",
                                transactionId,
                                exception.getMessage()
                        )
                )
                .doOnNext(
                        event -> log.info(
                                "Generated event TRANSACTION_ACTIVATED_EVENT for transactionId {}",
                                transactionId
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
                        null,
                        paymentRequestInfo.transferList().stream().map(
                                transfer -> new PaymentTransferInformation(
                                        transfer.paFiscalCode(),
                                        transfer.digitalStamp(),
                                        transfer.transferAmount(),
                                        transfer.transferCategory()
                                )
                        ).toList(),
                        paymentRequestInfo.isAllCCP()
                )
        ).toList();
    }
}
