package it.pagopa.transactions.commands.handlers.v2;

import com.azure.cosmos.implementation.apachecommons.collections.CollectionUtils;
import io.opentelemetry.api.common.Attributes;
import it.pagopa.ecommerce.commons.client.JwtIssuerClient;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.domain.v2.IdempotencyKey;
import it.pagopa.ecommerce.commons.domain.v2.PaymentTransferInfo;
import it.pagopa.ecommerce.commons.domain.v2.RptId;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.exceptions.JwtIssuerClientException;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.StringUtil;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenRequestDto;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenResponseDto;
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.v2.ReactivePaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.repositories.v2.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.transactions.client.JwtTokenIssuerClient;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.commands.data.NewTransactionRequestData;
import it.pagopa.transactions.commands.handlers.TransactionActivateHandlerCommon;
import it.pagopa.transactions.exceptions.DigitalStampNotAllowedForClientException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.NodoOperations;
import it.pagopa.transactions.utils.SpanLabelOpenTelemetry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
import java.util.*;

@Slf4j
@Component(TransactionActivateHandler.QUALIFIER_NAME)
public class TransactionActivateHandler extends TransactionActivateHandlerCommon {

    public static final String QUALIFIER_NAME = "transactionActivateHandlerV2";
    private final ReactivePaymentRequestInfoRedisTemplateWrapper reactivePaymentRequestInfoRedisTemplateWrapper;
    private final TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData> transactionEventActivatedStoreRepository;
    private final NodoOperations nodoOperations;
    private final QueueAsyncClient transactionActivatedQueueAsyncClientV2;

    @Autowired
    public TransactionActivateHandler(
            ReactivePaymentRequestInfoRedisTemplateWrapper reactivePaymentRequestInfoRedisTemplateWrapper,
            TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData> transactionEventActivatedStoreRepository,
            NodoOperations nodoOperations,
            @Qualifier(
                "transactionActivatedQueueAsyncClientV2"
            ) QueueAsyncClient transactionActivatedQueueAsyncClientV2,
            @Value("${payment.token.validity}") Integer paymentTokenTimeout,
            ConfidentialMailUtils confidentialMailUtils,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") int transientQueuesTTLSeconds,
            @Value("${nodo.parallelRequests}") int nodoParallelRequests,
            TracingUtils tracingUtils,
            OpenTelemetryUtils openTelemetryUtils,
            @Value("${payment.token.validity}") int jwtEcommerceValidityTimeInSeconds,
            JwtTokenIssuerClient jwtTokenIssuerClient
    ) {
        super(
                paymentTokenTimeout,
                jwtTokenIssuerClient,
                confidentialMailUtils,
                transientQueuesTTLSeconds,
                nodoParallelRequests,
                tracingUtils,
                openTelemetryUtils,
                jwtEcommerceValidityTimeInSeconds
        );
        this.reactivePaymentRequestInfoRedisTemplateWrapper = reactivePaymentRequestInfoRedisTemplateWrapper;
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
        boolean allowDigitalStamp = command.getClientId().equals(Transaction.ClientId.CHECKOUT_CART.toString())
                || command.getClientId().equals(Transaction.ClientId.WISP_REDIRECT.toString());

        return Mono.defer(
                () -> Flux.fromIterable(paymentNotices)
                        .parallel(nodoParallelRequests)
                        .runOn(Schedulers.parallel())
                        .flatMap(
                                paymentNotice -> getPaymentRequestInfoFromCache(paymentNotice.rptId(), paymentNotice)
                                        .map(
                                                paymentRequestInfo -> Tuples.of(
                                                        paymentNotice,
                                                        paymentRequestInfo
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

                                    final it.pagopa.ecommerce.commons.domain.v2.PaymentNotice paymentNotice = cacheResult
                                            .getT1();
                                    final PaymentRequestInfo partialPaymentRequestInfo = cacheResult.getT2();
                                    final IdempotencyKey idempotencyKey = partialPaymentRequestInfo.idempotencyKey();
                                    final RptId rptId = paymentNotice.rptId();

                                    return Optional.of(partialPaymentRequestInfo)
                                            .filter(requestInfo -> StringUtils.isNotBlank(requestInfo.paymentToken()))
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
                                                            .flatMap(
                                                                    p -> reactivePaymentRequestInfoRedisTemplateWrapper
                                                                            .save(p)
                                                                            .doOnNext(
                                                                                    ignored -> LogTracingUtils
                                                                                            .loggerTracingUtils()
                                                                                            .success()
                                                                                            .details(
                                                                                                    Map.of(
                                                                                                            "payment_request_info_id",
                                                                                                            p.id().value(),
                                                                                                            "payment_token",
                                                                                                            Objects.toString(
                                                                                                                    p.paymentToken()
                                                                                                            )
                                                                                                    )
                                                                                            )
                                                                                            .logInfo(
                                                                                                    log,
                                                                                                    "PaymentRequestInfo cache updated"
                                                                                            )
                                                                            )
                                                                            .thenReturn(p)

                                                            )
                                            );
                                }
                        )
                        .sequential()
                        .collectList()
                        .filter(paymentRequestInfoList -> {
                            if (allowDigitalStamp) {
                                return true;
                            }
                            return paymentRequestInfoList.stream().allMatch(
                                    paymentRequestInfo -> CollectionUtils.emptyIfNull(paymentRequestInfo.transferList())
                                            .stream()
                                            .allMatch(t -> Boolean.FALSE.equals(t.digitalStamp()))
                            );
                        })
                        .switchIfEmpty(Mono.error(new DigitalStampNotAllowedForClientException(command.getClientId())))
                        .doOnError(
                                e -> LogTracingUtils.loggerTracingUtils()
                                        .failure()
                                        .logError(log, e, e.getMessage())
                        )
                        .flatMap(
                                paymentRequestInfos -> generateTransactionJwtToken(command, transactionId)
                                        .map(token -> Tuples.of(token.getToken(), paymentRequestInfos))
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

    private Map<String, String> createClaimsMap(
                                                TransactionId transactionId,
                                                String orderId,
                                                UUID userId
    ) {
        Map<String, String> claimsMap = new HashMap<>();
        claimsMap.put(JwtIssuerClient.TRANSACTION_ID_CLAIM, transactionId.value());
        if (orderId != null) {
            claimsMap.put(JwtIssuerClient.ORDER_ID_CLAIM, orderId);
        }
        if (userId != null) {
            claimsMap.put(JwtIssuerClient.USER_ID_CLAIM, userId.toString());
        }
        return claimsMap;
    }

    private Mono<CreateTokenResponseDto> generateTransactionJwtToken(
                                                                     TransactionActivateCommand command,
                                                                     TransactionId transactionId
    ) {

        return Mono.just(createClaimsMap(transactionId, command.getData().orderId(), command.getUserId()))
                .flatMap(
                        claimsMap -> jwtTokenIssuerClient.createJWTToken(
                                new CreateTokenRequestDto()
                                        .duration(jwtEcommerceValidityTimeInSeconds)
                                        .audience(JwtIssuerClient.ECOMMERCE_AUDIENCE)
                                        .privateClaims(claimsMap)
                        )
                )
                .doOnError(
                        c -> Mono.error(
                                new JwtIssuerClientException(
                                        "Error while generating jwt token for ecommerce",
                                        c
                                )
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

    private Mono<PaymentRequestInfo> getPaymentRequestInfoFromCache(
                                                                    RptId rptId,
                                                                    it.pagopa.ecommerce.commons.domain.v2.PaymentNotice paymentNotice
    ) {
        return reactivePaymentRequestInfoRedisTemplateWrapper
                .findById(rptId.value())
                .map(requestInfo -> {
                    boolean isIdempotencyKeyValid = isValidIdempotencyKey(
                            requestInfo.idempotencyKey()
                    );

                    LogTracingUtils.loggerTracingUtils()
                            .success()
                            .dependency(LogTracingUtils.REDIS_DEPENDENCY)
                            .details(
                                    Map.of(
                                            "rpt_id",
                                            requestInfo.id().value(),
                                            "is_idempotency_key_valid",
                                            String.valueOf(isIdempotencyKeyValid)
                                    )
                            )
                            .logInfo(log, "PaymentRequestInfo cache hit");

                    if (isIdempotencyKeyValid) {
                        return requestInfo;
                    } else {
                        // if idempotency key is not valid we return a payment request info with
                        // idempotency key and due date valued
                        return new PaymentRequestInfo(
                                rptId,
                                null,
                                null,
                                null,
                                null,
                                requestInfo.dueDate(),
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
                    }
                }
                )
                .defaultIfEmpty(
                        new PaymentRequestInfo(
                                rptId,
                                null,
                                null,
                                null,
                                null,
                                null,
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
                        )
                )
                .doOnNext(
                        p -> LogTracingUtils.loggerTracingUtils()
                                .success()
                                .dependency(LogTracingUtils.REDIS_DEPENDENCY)
                                .details(
                                        Map.of("rpt_id", p.id().value())
                                )
                                .logInfo(log, "PaymentRequestInfo cache miss")
                );
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

        return transactionActivatedEvent.flatMap(transactionEventActivatedStoreRepository::insert)
                .flatMap(
                        e -> tracingUtils.traceMono(
                                this.getClass().getSimpleName(),
                                tracingInfo -> transactionActivatedQueueAsyncClientV2.sendMessageWithResponse(
                                        new QueueEvent<>(e, tracingInfo),
                                        Duration.ofSeconds(paymentTokenTimeout),
                                        Duration.ofSeconds(transientQueuesTTLSeconds)
                                )
                        ).doOnError(
                                exception -> LogTracingUtils.loggerTracingUtils()
                                        .failure()
                                        .logError(log, exception, "Error on TRANSACTION_ACTIVATED_EVENT generation")
                        )
                                .doOnNext(
                                        event -> LogTracingUtils.loggerTracingUtils()
                                                .success()
                                                .logInfo(log, "Generated event TRANSACTION_ACTIVATED_EVENT")
                                )
                                .thenReturn(e)

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
                        CollectionUtils.emptyIfNull(paymentRequestInfo.transferList()).stream().map(
                                transfer -> new PaymentTransferInformation(
                                        transfer.paFiscalCode(),
                                        transfer.digitalStamp(),
                                        transfer.transferAmount(),
                                        transfer.transferCategory()
                                )
                        ).toList(),
                        Boolean.TRUE.equals(paymentRequestInfo.isAllCCP()),
                        paymentRequestInfo.paName(),
                        paymentRequestInfo.creditorReferenceId()
                )
        ).toList();
    }
}
