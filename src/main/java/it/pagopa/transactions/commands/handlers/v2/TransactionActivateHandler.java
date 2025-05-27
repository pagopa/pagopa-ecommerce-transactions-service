package it.pagopa.transactions.commands.handlers.v2;

import io.opentelemetry.api.common.Attributes;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.domain.v2.Claims;
import it.pagopa.ecommerce.commons.domain.v2.IdempotencyKey;
import it.pagopa.ecommerce.commons.domain.v2.RptId;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.redis.templatewrappers.v2.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.repositories.v2.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.utils.v2.JwtTokenUtils;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.commands.data.NewTransactionRequestData;
import it.pagopa.transactions.commands.handlers.TransactionActivateHandlerCommon;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.NodoOperations;
import it.pagopa.transactions.utils.SpanLabelOpenTelemetry;
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

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component(TransactionActivateHandler.QUALIFIER_NAME)
public class TransactionActivateHandler extends TransactionActivateHandlerCommon {

    public static final String QUALIFIER_NAME = "transactionActivateHandlerV2";
    private final PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper;
    private final TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData> transactionEventActivatedStoreRepository;
    private final NodoOperations nodoOperations;
    private final QueueAsyncClient transactionActivatedQueueAsyncClientV2;

    @Autowired
    public TransactionActivateHandler(
            PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper,
            TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData> transactionEventActivatedStoreRepository,
            NodoOperations nodoOperations,
            JwtTokenUtils jwtTokenUtils,
            @Qualifier(
                "transactionActivatedQueueAsyncClientV2"
            ) QueueAsyncClient transactionActivatedQueueAsyncClientV2,
            @Value("${payment.token.validity}") Integer paymentTokenTimeout,
            ConfidentialMailUtils confidentialMailUtils,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") int transientQueuesTTLSeconds,
            @Value("${nodo.parallelRequests}") int nodoParallelRequests,
            TracingUtils tracingUtils,
            OpenTelemetryUtils openTelemetryUtils,
            @Qualifier("ecommerceSigningKey") SecretKey ecommerceSigningKey,
            @Value("${payment.token.validity}") int jwtEcommerceValidityTimeInSeconds
    ) {
        super(
                paymentTokenTimeout,
                jwtTokenUtils,
                confidentialMailUtils,
                transientQueuesTTLSeconds,
                nodoParallelRequests,
                tracingUtils,
                openTelemetryUtils,
                ecommerceSigningKey,
                jwtEcommerceValidityTimeInSeconds
        );
        this.paymentRequestInfoRedisTemplateWrapper = paymentRequestInfoRedisTemplateWrapper;
        this.transactionEventActivatedStoreRepository = transactionEventActivatedStoreRepository;
        this.nodoOperations = nodoOperations;
        this.transactionActivatedQueueAsyncClientV2 = transactionActivatedQueueAsyncClientV2;
    }

    public Mono<Tuple2<Mono<BaseTransactionEvent<?>>, String>> handle(
                                                                      TransactionActivateCommand command
    ) {
        final TransactionId transactionId = command.getTransactionId();
        final NewTransactionRequestData newTransactionRequestDto = command.getData();
        final List<it.pagopa.ecommerce.commons.domain.v2.PaymentNotice> paymentNotices = newTransactionRequestDto
                .paymentNoticeList();
        final boolean multiplePaymentNotices = paymentNotices.size() > 1;
        log.info(
                "Parallel processed Nodo activation requests : [{}]. Multiple payment notices: [{}]. Id cart: [{}]",
                nodoParallelRequests,
                multiplePaymentNotices,
                Optional.ofNullable(newTransactionRequestDto.idCard()).orElse("id cart not found")
        );
        return Mono.defer(
                () -> Flux.fromIterable(paymentNotices)
                        .parallel(nodoParallelRequests)
                        .runOn(Schedulers.parallel())
                        .flatMap(
                                paymentNotice -> Mono.just(
                                        Tuples.of(
                                                paymentNotice,
                                                getPaymentRequestInfoFromCache(paymentNotice.rptId())
                                        )
                                )
                        ).flatMap(
                                paymentRequest -> {
                                    final it.pagopa.ecommerce.commons.domain.v2.PaymentNotice paymentNotice = paymentRequest
                                            .getT1();
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

                                                                                paymentNotice.rptId(),

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
                                                                                null,
                                                                                paymentNotice.creditorReferenceId()
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

                                    final it.pagopa.ecommerce.commons.domain.v2.PaymentNotice paymentNotice = cacheResult
                                            .getT1();
                                    final PaymentRequestInfo partialPaymentRequestInfo = cacheResult.getT2();
                                    final IdempotencyKey idempotencyKey = partialPaymentRequestInfo.idempotencyKey();
                                    final RptId rptId = paymentNotice.rptId();

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
                                                                    paymentNotice.transactionAmount().value(),
                                                                    transactionId.value(),
                                                                    paymentTokenTimeout,
                                                                    newTransactionRequestDto.idCard(),
                                                                    partialPaymentRequestInfo.dueDate(),
                                                                    Transaction.ClientId
                                                                            .fromString(command.getClientId())
                                                            )
                                                            .doOnSuccess(
                                                                    p -> {
                                                                        log.info(
                                                                                "PaymentRequestInfo cache update for [{}] with paymentToken [{}]",
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
                                paymentRequestInfos ->
                                        /*jwtTokenUtils
                                        .generateToken(
                                                ecommerceSigningKey,
                                                jwtEcommerceValidityTimeInSeconds,
                                                new Claims(
                                                        transactionId,
                                                        command.getData().orderId(),
                                                        null,
                                                        command.getUserId()
                                                )
                                        ) */
                                        .fold(
                                                Mono::error,
                                                generatedToken -> Mono.just(
                                                        Tuples.of(generatedToken, paymentRequestInfos)
                                                )
                                        )
                        ).flatMap(
                                args -> {
                                    String authToken = args.getT1();
                                    List<PaymentRequestInfo> paymentRequestsInfo = args.getT2();
                                    return Mono.just(
                                            Tuples.of(
                                                    newTransactionActivatedEvent(
                                                            command,
                                                            paymentRequestsInfo,
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
                    SpanLabelOpenTelemetry.REPEATED_ACTIVATION_SPAN_NAME,
                    Attributes.of(
                            SpanLabelOpenTelemetry.REPEATED_ACTIVATION_PAYMENT_TOKEN_ATTRIBUTE_KEY,
                            paymentToken,
                            SpanLabelOpenTelemetry.REPEATED_ACTIVATION_PAYMENT_TOKEN_LEFT_TIME_ATTRIBUTE_KEY,
                            paymentTokenValidityTimeLeft.getSeconds()
                    )
            );
            log.info(
                    "PaymentRequestInfo cache hit for {} with valid paymentToken {}. Validity left time: {}",
                    paymentRequestInfo.id().value(),
                    paymentRequestInfo.paymentToken(),
                    paymentTokenValidityTimeLeft
            );
        } else {
            log.error(
                    "Cannot trace repeated transaction activation for {} with payment token: {}, missing transaction activation date",
                    paymentRequestInfo.id().value(),
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

    private Mono<BaseTransactionEvent<?>> newTransactionActivatedEvent(
                                                                       TransactionActivateCommand command,
                                                                       List<PaymentRequestInfo> paymentRequestsInfo,
                                                                       Integer paymentTokenTimeout
    ) {
        NewTransactionRequestData newTransactionRequestData = command.getData();
        TransactionId transactionId = command.getTransactionId();
        List<PaymentNotice> paymentNotices = toPaymentNoticeList(paymentRequestsInfo);
        Mono<it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData> data = command.getData().email()
                .map(
                        e -> new it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData(
                                e,
                                paymentNotices,
                                null,
                                null,
                                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId
                                        .valueOf(command.getClientId()),
                                newTransactionRequestData.idCard(),
                                paymentTokenTimeout,
                                newTransactionRequestData.orderId() != null
                                        ? new NpgTransactionGatewayActivationData(
                                                newTransactionRequestData.orderId(),
                                                newTransactionRequestData.correlationId().toString()
                                        )// this logic will be eliminated with task CHK-2286 by handling the saving of
                                         // correlationId only
                                        : new EmptyTransactionGatewayActivationData(),
                                Optional.ofNullable(command.getUserId()).map(UUID::toString).orElse(null)
                        )
                );

        Mono<it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent> transactionActivatedEvent = data.map(
                d -> new it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent(
                        transactionId.value(),
                        d
                )
        );

        return transactionActivatedEvent.flatMap(transactionEventActivatedStoreRepository::save)
                .flatMap(
                        e -> tracingUtils.traceMono(
                                this.getClass().getSimpleName(),
                                tracingInfo -> transactionActivatedQueueAsyncClientV2.sendMessageWithResponse(
                                        new QueueEvent<>(e, tracingInfo),
                                        Duration.ofSeconds(paymentTokenTimeout),
                                        Duration.ofSeconds(transientQueuesTTLSeconds)
                                )
                        ).doOnError(
                                exception -> log.error(
                                        "Error to generate event TRANSACTION_ACTIVATED_EVENT for transactionId {} - error {}",
                                        transactionId.value(),
                                        exception.getMessage()
                                )
                        )
                                .doOnNext(
                                        event -> log.info(
                                                "Generated event TRANSACTION_ACTIVATED_EVENT for transactionId {}",
                                                transactionId.value()
                                        )
                                ).thenReturn(e)

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
                        paymentRequestInfo.isAllCCP(),
                        paymentRequestInfo.paName(),
                        paymentRequestInfo.creditorReferenceId()
                )
        ).toList();
    }
}
