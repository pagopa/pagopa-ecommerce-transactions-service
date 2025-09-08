package it.pagopa.transactions.commands.handlers.v2;

import com.azure.cosmos.implementation.InternalServerErrorException;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData.PaymentGateway;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.TransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.WalletInfo;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.redis.templatewrappers.ExclusiveLockDocumentWrapper;
import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.JwtTokenIssuerClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationOutput;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.handlers.TransactionRequestAuthorizationHandlerCommon;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.LockNotAcquiredException;
import it.pagopa.transactions.repositories.TransactionTemplateWrapper;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component("TransactionRequestAuthorizationHandlerV2")
@Slf4j
public class TransactionRequestAuthorizationHandler extends TransactionRequestAuthorizationHandlerCommon {

    private final TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;
    private final TransactionsUtils transactionsUtils;

    private final EcommercePaymentMethodsClient paymentMethodsClient;

    protected final TracingUtils tracingUtils;
    protected final OpenTelemetryUtils openTelemetryUtils;
    private final QueueAsyncClient transactionAuthorizationRequestedQueueAsyncClientV2;

    protected final Integer authRequestEventVisibilityTimeoutSeconds;
    protected final Integer transientQueuesTTLSeconds;

    private final UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils;

    private final ExclusiveLockDocumentWrapper exclusiveLockDocumentWrapper;

    @Autowired
    public TransactionRequestAuthorizationHandler(
            PaymentGatewayClient paymentGatewayClient,
            TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository,
            TransactionsUtils transactionsUtils,
            @Value("${checkout.basePath}") String checkoutBasePath,
            @Value("${checkout.npg.gdi.url}") String checkoutNpgGdiUrl,
            @Value("${checkout.outcome.url}") String checkoutOutcomeUrl,
            EcommercePaymentMethodsClient paymentMethodsClient,
            TransactionTemplateWrapper transactionTemplateWrapper,
            @Qualifier(
                "transactionAuthorizationRequestedQueueAsyncClientV2"
            ) QueueAsyncClient transactionAuthorizationRequestedQueueAsyncClientV2,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") Integer transientQueuesTTLSeconds,
            @Value("${authorization.event.visibilityTimeoutSeconds}") Integer authRequestEventVisibilityTimeoutSeconds,
            TracingUtils tracingUtils,
            OpenTelemetryUtils openTelemetryUtils,
            JwtTokenIssuerClient jwtTokenIssuerClient,
            @Value("${npg.notification.jwt.validity.time}") int jwtWebviewValidityTimeInSeconds,
            UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils,
            ExclusiveLockDocumentWrapper exclusiveLockDocumentWrapper
    ) {
        super(
                paymentGatewayClient,
                checkoutBasePath,
                checkoutNpgGdiUrl,
                checkoutOutcomeUrl,
                transactionTemplateWrapper,
                jwtTokenIssuerClient,
                jwtWebviewValidityTimeInSeconds
        );
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.transactionsUtils = transactionsUtils;
        this.paymentMethodsClient = paymentMethodsClient;
        this.tracingUtils = tracingUtils;
        this.openTelemetryUtils = openTelemetryUtils;
        this.transactionAuthorizationRequestedQueueAsyncClientV2 = transactionAuthorizationRequestedQueueAsyncClientV2;
        this.authRequestEventVisibilityTimeoutSeconds = authRequestEventVisibilityTimeoutSeconds;
        this.transientQueuesTTLSeconds = transientQueuesTTLSeconds;
        this.updateTransactionStatusTracerUtils = updateTransactionStatusTracerUtils;
        this.exclusiveLockDocumentWrapper = exclusiveLockDocumentWrapper;
    }

    @Override
    public Mono<RequestAuthorizationResponseDto> handle(TransactionRequestAuthorizationCommand command) {
        return transactionsUtils.reduceEventsV2(command.getData().transactionId())
                        .filter(t -> t.getStatus().equals(TransactionStatusDto.ACTIVATED))
                        .cast(TransactionActivated.class)
                .flatMap(transactionActivated -> handleWithCreationDate(command, transactionActivated))
                .map(Tuple2::getT1);
    }

    public Mono<Tuple2<RequestAuthorizationResponseDto, TransactionAuthorizationRequestedEvent>> handleWithCreationDate(TransactionRequestAuthorizationCommand command, TransactionActivated transaction) {
        AuthorizationRequestData authorizationRequestData = command.getData();
        URI logo = getLogo(command.getData());

        Mono<? extends BaseTransaction> alreadyProcessedError = Mono.just(transaction)
                .cast(BaseTransaction.class)
                .doOnNext(
                        t -> log.warn(
                                "Invalid state transition: requested authorization for transaction {} from status {}",
                                t.getTransactionId(),
                                t.getStatus()
                        )
                ).flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));

        Mono<TransactionActivated> transactionActivated = Mono.just(transaction)
                .cast(BaseTransaction.class)
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionActivated.class);

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> monoNpg = transactionActivated
                .flatMap(
                        tx -> npgAuthRequestPipeline(
                                authorizationRequestData,
                                tx.getTransactionActivatedData()
                                        .getTransactionGatewayActivationData() instanceof NpgTransactionGatewayActivationData transactionGatewayActivationData
                                        ? transactionGatewayActivationData.getCorrelationId()
                                        : null,
                                tx.getClientId().getEffectiveClient().name(),
                                command.lang,
                                Optional.ofNullable(tx.getTransactionActivatedData().getUserId())
                                        .map(UUID::fromString).orElse(null)
                        )
                ).map(
                        authorizationOutput -> Tuples.of(
                                authorizationOutput,
                                PaymentGateway.NPG
                        )
                );

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> monoRedirect = transactionActivated
                .flatMap(
                        tx -> redirectionAuthRequestPipeline(
                                authorizationRequestData,
                                tx.getClientId().getEffectiveClient(),
                                Optional.ofNullable(tx.getTransactionActivatedData().getUserId())
                                        .map(UUID::fromString).orElse(null)
                        )
                ).map(
                        authorizationOutput -> Tuples.of(
                                authorizationOutput,
                                PaymentGateway.REDIRECT
                        )
                );

        List<Mono<Tuple2<AuthorizationOutput, PaymentGateway>>> gatewayRequests = List
                .of(monoNpg, monoRedirect);

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> gatewayAttempts = gatewayRequests
                .stream()
                .reduce(
                        Mono::switchIfEmpty
                )
                .orElse(Mono.empty());
        return transactionActivated
                .flatMap(t -> switch (command.getData().authDetails()) {
                    case CardsAuthRequestDetailsDto authRequestDetails -> paymentMethodsClient.updateSession(
                            command.getData().paymentInstrumentId(),
                            authRequestDetails.getOrderId(),
                            command.getData().transactionId().value()
                    ).thenReturn(t);
                    default -> Mono.just(t);
                })
                .map(t -> {
                    TransactionId transactionId = t.getTransactionId();
                    ExclusiveLockDocument lockDocument = new ExclusiveLockDocument(
                            "POST-auth-request-%s".formatted(transactionId.value()),
                            "transactions-service"
                    );
                    // fix CHK-3222: auth request operations are not idempotent on the NPG side, so
                    // this lock prevents multiple auth request to be performed for a single
                    // transaction (for timeouts scenarios, etc.). The lock duration has been set to
                    // the payment token validity time in order to make this API call performable
                    // only once per transaction (further attempts will find the transaction in an
                    // expired status and return an error)
                    boolean lockAcquired = exclusiveLockDocumentWrapper.saveIfAbsent(
                            lockDocument,
                            Duration.ofSeconds(t.getTransactionActivatedData().getPaymentTokenValiditySeconds())
                    );
                    log.info(
                            "requestTransactionAuthorization lock acquired for transactionId: [{}] with key: [{}]: [{}]",
                            transactionId,
                            lockDocument.id(),
                            lockAcquired
                    );
                    if (!lockAcquired) {
                        throw new LockNotAcquiredException(transactionId, lockDocument);
                    }
                    return t;
                })
                .flatMap(
                        t -> gatewayAttempts.switchIfEmpty(Mono.error(new InvalidRequestException("No gateway matched")))
                                .doOnSuccess(authOutput ->
                                        traceAuthorizationRequestedOperation(
                                                authorizationRequestData,
                                                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                                                new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult(
                                                        AuthorizationOutcomeDto.OK.toString(),
                                                        Optional.empty()
                                                ),
                                                t.getClientId()
                                        )
                                ).doOnError(exception ->
                                        traceAuthorizationRequestedOperation(
                                                authorizationRequestData,
                                                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.PROCESSING_ERROR,
                                                new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult(
                                                        AuthorizationOutcomeDto.KO.toString(),
                                                        Optional
                                                                .of(exception)
                                                                .map(Throwable::getMessage)
                                                ),
                                                t.getClientId()
                                        )
                                )
                                .flatMap(authorizationOutputAndPaymentGateway -> {
                                    log.info(
                                            "Logging authorization event for transaction id {}",
                                            t.getTransactionId().value()
                                    );
                                    AuthorizationOutput authorizationOutput = authorizationOutputAndPaymentGateway
                                            .getT1();
                                    PaymentGateway paymentGateway = authorizationOutputAndPaymentGateway.getT2();
                                    String brand = authorizationRequestData.brand();
                                    TransactionGatewayAuthorizationRequestedData transactionGatewayAuthorizationRequestedData = switch (paymentGateway) {
                                        case NPG -> new NpgTransactionGatewayAuthorizationRequestedData(
                                                logo,
                                                brand,
                                                authorizationRequestData
                                                        .authDetails() instanceof WalletAuthRequestDetailsDto
                                                        || authorizationRequestData
                                                        .authDetails() instanceof ApmAuthRequestDetailsDto
                                                        ? authorizationOutput.npgSessionId()
                                                        .orElseThrow(
                                                                () -> new InternalServerErrorException(
                                                                        "Cannot retrieve session id for transaction"
                                                                )
                                                        ) // build session id
                                                        : authorizationRequestData.sessionId()
                                                        .orElseThrow(
                                                                () -> new BadGatewayException(
                                                                        "Cannot retrieve session id for transaction",
                                                                        HttpStatus.INTERNAL_SERVER_ERROR
                                                                )
                                                        ),
                                                authorizationOutput.npgConfirmSessionId().orElse(null),
                                                authorizationRequestData
                                                        .authDetails() instanceof WalletAuthRequestDetailsDto walletAuthRequestDetailsDto ?
                                                        new WalletInfo(walletAuthRequestDetailsDto.getWalletId(), null) : null
                                        );
                                        case REDIRECT -> new RedirectTransactionGatewayAuthorizationRequestedData(
                                                logo,
                                                authorizationOutput.authorizationTimeoutMillis().orElse(600000)
                                        );
                                        default ->
                                                throw new InvalidRequestException("Unhandled payment gateway: [%s]".formatted(paymentGateway));
                                    };
                                    TransactionAuthorizationRequestedEvent authorizationEvent = new TransactionAuthorizationRequestedEvent(
                                            t.getTransactionId().value(),
                                            new TransactionAuthorizationRequestData(
                                                    t.getPaymentNotices().stream()
                                                            .mapToInt(
                                                                    paymentNotice -> paymentNotice.transactionAmount()
                                                                            .value()
                                                            ).sum(),
                                                    command.getData().fee(),
                                                    command.getData().paymentInstrumentId(),
                                                    command.getData().pspId(),
                                                    command.getData().paymentTypeCode(),
                                                    command.getData().brokerName(),
                                                    command.getData().pspChannelCode(),
                                                    command.getData().paymentMethodName(),
                                                    command.getData().pspBusinessName(),
                                                    command.getData().pspOnUs(),
                                                    authorizationOutput.authorizationId(),
                                                    paymentGateway,
                                                    command.getData().paymentMethodDescription(),
                                                    transactionGatewayAuthorizationRequestedData,
                                                    command.getData().idBundle()
                                            )
                                    );

                                    return transactionEventStoreRepository.save(authorizationEvent)
                                            .doOnNext(e -> {
                                                String authorizationRequestId = e.getData().getAuthorizationRequestId();
                                                String transactionId = t.getTransactionId().value();
                                                log.info("Saved the TRANSACTION_AUTHORIZATION_REQUESTED_EVENT event for transactionId: [{}] and authorizationRequestId: [{}]", transactionId, authorizationRequestId);
                                            })
                                            .flatMap(
                                                    savedEvent -> Mono
                                                            .just(
                                                                    savedEvent.getData()
                                                                            .getPaymentGateway()
                                                            )
                                                            .flatMap(
                                                                    p -> tracingUtils.traceMono(
                                                                            this.getClass().getSimpleName(),
                                                                            tracingInfo -> transactionAuthorizationRequestedQueueAsyncClientV2
                                                                                    .sendMessageWithResponse(
                                                                                            new QueueEvent<>(
                                                                                                    savedEvent,
                                                                                                    tracingInfo
                                                                                            ),
                                                                                            Duration.ofSeconds(
                                                                                                    authRequestEventVisibilityTimeoutSeconds
                                                                                            ),
                                                                                            Duration.ofSeconds(
                                                                                                    transientQueuesTTLSeconds
                                                                                            )
                                                                                    )
                                                                    )
                                                            )
                                                            .map(queueResponse -> Tuples.of(
                                                                    new RequestAuthorizationResponseDto()
                                                                            .authorizationUrl(
                                                                                    authorizationOutput.authorizationUrl()
                                                                            )
                                                                            .authorizationRequestId(
                                                                                    authorizationOutput.authorizationId()
                                                                            ),
                                                                    savedEvent
                                                            ))
                                            );
                                })
                                .doOnError(error -> log.error("Error performing authorization", error))
                );
    }

    /**
     * Trace authorization requested operations
     *
     * @param authorizationRequestData authorization requested data
     * @param outcome                  operation outcome
     * @param gatewayOutcomeResult     authorization gateway result
     * @param clientId                 transaction client id
     */
    private void traceAuthorizationRequestedOperation(
                                                      AuthorizationRequestData authorizationRequestData,
                                                      UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome outcome,
                                                      UpdateTransactionStatusTracerUtils.GatewayOutcomeResult gatewayOutcomeResult,
                                                      Transaction.ClientId clientId
    ) {
        updateTransactionStatusTracerUtils.traceStatusUpdateOperation(
                new UpdateTransactionStatusTracerUtils.AuthorizationRequestedStatusUpdate(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger
                                .from(PaymentGateway.valueOf(authorizationRequestData.paymentGatewayId())),
                        outcome,
                        authorizationRequestData.pspId(),
                        authorizationRequestData.paymentTypeCode(),
                        clientId,
                        authorizationRequestData.authDetails() instanceof WalletAuthRequestDetailsDto,
                        gatewayOutcomeResult
                )
        );
    }

    /**
     * Redirection authorization pipeline
     *
     * @param authorizationData authorization data
     * @param clientId          client that initiated the transaction
     * @return a tuple of redirection url, psp authorization id and authorization
     *         timeout
     */
    protected Mono<AuthorizationOutput> redirectionAuthRequestPipeline(
                                                                       AuthorizationRequestData authorizationData,
                                                                       Transaction.ClientId clientId,
                                                                       UUID userId

    ) {
        Transaction.ClientId effectiveClient = switch (clientId) {
            case CHECKOUT_CART -> Transaction.ClientId.CHECKOUT;
            default -> clientId;
        };

        RedirectUrlRequestDto.TouchpointEnum touchpoint = RedirectUrlRequestDto.TouchpointEnum
                .valueOf(effectiveClient.name());

        return redirectionAuthRequestPipeline(authorizationData, touchpoint, userId);
    }

}
