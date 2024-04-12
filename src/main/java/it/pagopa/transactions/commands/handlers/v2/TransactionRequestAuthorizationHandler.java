package it.pagopa.transactions.commands.handlers.v2;

import com.azure.cosmos.implementation.BadRequestException;
import com.azure.cosmos.implementation.InternalServerErrorException;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData.PaymentGateway;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.utils.JwtTokenUtils;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.transactions.server.model.ApmAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.CardsAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.generated.transactions.server.model.WalletAuthRequestDetailsDto;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.client.WalletAsyncQueueClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationOutput;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.handlers.TransactionRequestAuthorizationHandlerCommon;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.repositories.TransactionTemplateWrapper;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.OpenTelemetryUtils;
import it.pagopa.transactions.utils.PaymentSessionData;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import javax.crypto.SecretKey;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
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

    private final Optional<WalletAsyncQueueClient> walletAsyncQueueClient;

    protected final Integer npgAuthRequestTimeout;
    protected final Integer transientQueuesTTLSeconds;

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
            @Qualifier(
                "walletAsyncQueueClient"
            ) Optional<WalletAsyncQueueClient> walletAsyncQueueClient,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") Integer transientQueuesTTLSeconds,
            @Value("${npg.authorization.request.timeout.seconds}") Integer npgAuthRequestTimeout,
            TracingUtils tracingUtils,
            OpenTelemetryUtils openTelemetryUtils,
            JwtTokenUtils jwtTokenUtils,
            @Qualifier("ecommerceWebViewSigningKey") SecretKey ecommerceWebViewSigningKey,
            @Value("${npg.notification.jwt.validity.time}") int jwtWebviewValidityTimeInSeconds
    ) {
        super(
                paymentGatewayClient,
                checkoutBasePath,
                checkoutNpgGdiUrl,
                checkoutOutcomeUrl,
                transactionTemplateWrapper,
                jwtTokenUtils,
                ecommerceWebViewSigningKey,
                jwtWebviewValidityTimeInSeconds
        );
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.transactionsUtils = transactionsUtils;
        this.paymentMethodsClient = paymentMethodsClient;
        this.tracingUtils = tracingUtils;
        this.openTelemetryUtils = openTelemetryUtils;
        this.transactionAuthorizationRequestedQueueAsyncClientV2 = transactionAuthorizationRequestedQueueAsyncClientV2;
        this.npgAuthRequestTimeout = npgAuthRequestTimeout;
        this.transientQueuesTTLSeconds = transientQueuesTTLSeconds;
        this.walletAsyncQueueClient = walletAsyncQueueClient;
    }

    @Override
    public Mono<RequestAuthorizationResponseDto> handle(TransactionRequestAuthorizationCommand command) {
        AuthorizationRequestData authorizationRequestData = command.getData();
        URI logo = getLogo(command.getData());
        Mono<BaseTransaction> transaction = transactionsUtils.reduceEventsV2(
                command.getData().transactionId()
        );
        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .cast(BaseTransaction.class)
                .doOnNext(
                        t -> log.warn(
                                "Invalid state transition: requested authorization for transaction {} from status {}",
                                t.getTransactionId(),
                                t.getStatus()
                        )
                ).flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));
        Mono<TransactionActivated> transactionActivated = transaction
                .filter(t -> t.getStatus() == TransactionStatusDto.ACTIVATED)
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionActivated.class);

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> monoXPay = xpayAuthRequestPipeline(
                authorizationRequestData
        ).map(
                authorizationOutput -> Tuples.of(
                        authorizationOutput,
                        PaymentGateway.XPAY
                )
        );

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> monoVPOS = vposAuthRequestPipeline(
                authorizationRequestData
        ).map(
                authorizationOutput -> Tuples.of(
                        authorizationOutput,
                        PaymentGateway.VPOS
                )
        );

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> monoNpg = transactionActivated
                .flatMap(
                        tx -> npgAuthRequestPipeline(
                                authorizationRequestData,
                                tx.getTransactionActivatedData()
                                        .getTransactionGatewayActivationData()instanceof NpgTransactionGatewayActivationData transactionGatewayActivationData
                                                ? transactionGatewayActivationData.getCorrelationId()
                                                : null,
                                tx.getClientId().name(),
                                Optional.ofNullable(tx.getTransactionActivatedData().getUserId())
                                        .filter(Objects::nonNull).map(t -> UUID.fromString(t)).orElse(null)
                        )

                ).map(
                        authorizationOutput -> Tuples.of(
                                authorizationOutput,
                                PaymentGateway.NPG
                        )
                );

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> monoRedirect = transaction
                .map(BaseTransaction::getClientId).flatMap(
                        clientId -> redirectionAuthRequestPipeline(
                                authorizationRequestData,
                                clientId
                        )
                ).map(
                        authorizationOutput -> Tuples.of(
                                authorizationOutput,
                                PaymentGateway.REDIRECT
                        )
                );

        List<Mono<Tuple2<AuthorizationOutput, PaymentGateway>>> gatewayRequests = List
                .of(monoXPay, monoVPOS, monoNpg, monoRedirect);

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> gatewayAttempts = gatewayRequests
                .stream()
                .reduce(
                        Mono::switchIfEmpty
                ).orElse(Mono.empty());

        return transactionActivated
                .doOnNext(t -> this.fireWalletLastUsageEvent(command, t))
                .flatMap(t -> switch (command.getData().authDetails()) {
                    case CardsAuthRequestDetailsDto authRequestDetails -> paymentMethodsClient.updateSession(
                            command.getData().paymentInstrumentId(),
                            authRequestDetails.getOrderId(),
                            command.getData().transactionId().value()
                    ).thenReturn(t);
                    default -> Mono.just(t);
                })
                .flatMap(
                        t -> gatewayAttempts.switchIfEmpty(Mono.error(new BadRequestException("No gateway matched")))
                                .flatMap(authorizationOutputAndPaymentGateway -> {
                                    log.info(
                                            "Logging authorization event for transaction id {}",
                                            t.getTransactionId().value()
                                    );
                                    AuthorizationOutput authorizationOutput = authorizationOutputAndPaymentGateway
                                            .getT1();
                                    PaymentGateway paymentGateway = authorizationOutputAndPaymentGateway.getT2();
                                    String brand = authorizationRequestData.paymentSessionData().brand();
                                    TransactionGatewayAuthorizationRequestedData transactionGatewayAuthorizationRequestedData = switch (paymentGateway) {
                                        case VPOS, XPAY -> new PgsTransactionGatewayAuthorizationRequestedData(
                                                logo,
                                                PgsTransactionGatewayAuthorizationRequestedData.CardBrand.valueOf(brand)
                                        );
                                        case NPG -> {
                                            WalletInfo walletInfo = switch (command.getData().authDetails()) {
                                                case WalletAuthRequestDetailsDto walletDetails -> {
                                                    WalletInfo.WalletDetails walletInfoDetails = switch (command.getData().paymentSessionData()) {
                                                        case PaymentSessionData.WalletCardSessionData walletCardSessionData -> new WalletInfo.CardWalletDetails(
                                                                walletCardSessionData.cardBin(),
                                                                walletCardSessionData.lastFourDigits()
                                                        );
                                                        case PaymentSessionData.WalletPayPalSessionData walletPayPalSessionData -> new WalletInfo.PaypalWalletDetails(
                                                                walletPayPalSessionData.maskedEmail()
                                                        );
                                                        default -> throw new IllegalStateException("Unhandled wallet authorization request details: " + walletDetails.getDetailType());
                                                    };

                                                    yield new WalletInfo(
                                                            walletDetails.getWalletId(),
                                                            walletInfoDetails
                                                    );
                                                }
                                                default -> null;
                                            };

                                            String sessionId = switch (command.getData().paymentSessionData()) {
                                                case PaymentSessionData.CardSessionData cardSessionData ->
                                                        cardSessionData.sessionId();
                                                default -> authorizationOutput.npgSessionId().orElseThrow(
                                                        () -> new BadGatewayException(
                                                                "Cannot retrieve session id for transaction",
                                                                HttpStatus.INTERNAL_SERVER_ERROR
                                                        ));
                                            };

                                            yield new NpgTransactionGatewayAuthorizationRequestedData(
                                                    logo,
                                                    brand,
                                                    sessionId,
                                                    authorizationOutput.npgConfirmSessionId().orElse(null),
                                                    walletInfo
                                            );
                                        }
                                        case REDIRECT -> new RedirectTransactionGatewayAuthorizationRequestedData(
                                                logo,
                                                authorizationOutput.authorizationTimeoutMillis().orElse(600000)
                                        );
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
                                                    transactionGatewayAuthorizationRequestedData
                                            )
                                    );

                                    return transactionEventStoreRepository.save(authorizationEvent)
                                                    .flatMap(
                                                            e -> Mono
                                                                    .just(
                                                                            e.getData()
                                                                                    .getPaymentGateway()
                                                                    )
                                                                    .filter(
                                                                            gateway -> gateway
                                                                                    .equals(PaymentGateway.NPG)
                                                                    )
                                                                    .flatMap(
                                                                            p -> tracingUtils.traceMono(
                                                                                    this.getClass().getSimpleName(),
                                                                                    tracingInfo -> transactionAuthorizationRequestedQueueAsyncClientV2
                                                                                            .sendMessageWithResponse(
                                                                                                    new QueueEvent<>(
                                                                                                            e,
                                                                                                            tracingInfo
                                                                                                    ),
                                                                                                    Duration.ofSeconds(
                                                                                                            npgAuthRequestTimeout
                                                                                                    ),
                                                                                                    Duration.ofSeconds(
                                                                                                            transientQueuesTTLSeconds
                                                                                                    )
                                                                                            )
                                                                            )
                                                                    )
                                                    )
                                                    .thenReturn(authorizationOutput)
                                                    .map(
                                                            auth -> new RequestAuthorizationResponseDto()
                                                                    .authorizationUrl(
                                                                            authorizationOutput.authorizationUrl()
                                                                    )
                                                                    .authorizationRequestId(
                                                                            authorizationOutput.authorizationId()
                                                                    )
                                                    );
                                })
                                .doOnError(BadRequestException.class, error -> log.error(error.getMessage()))
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
                                                                       Transaction.ClientId clientId

    ) {
        Transaction.ClientId effectiveClient = switch (clientId) {
            case CHECKOUT_CART -> Transaction.ClientId.CHECKOUT;
            default -> clientId;
        };

        RedirectUrlRequestDto.TouchpointEnum touchpoint = RedirectUrlRequestDto.TouchpointEnum
                .valueOf(effectiveClient.name());

        return redirectionAuthRequestPipeline(authorizationData, touchpoint);
    }

    /**
     * Emit Wallet Used event on wallet queue. The semantic
     * of this method is fire-and-forget, so any action performed by this
     * method is executed asynchronously.
     * e.g. doOnNext(_ -> fireWalletLastUsageEvent(...))
     */
    private void fireWalletLastUsageEvent(
            TransactionRequestAuthorizationCommand command,
            TransactionActivated transactionActivated
    ) {
        final var wallet = switch (command.getData().authDetails()) {
            case WalletAuthRequestDetailsDto walletData -> Mono.just(walletData);
            default -> Mono.<WalletAuthRequestDetailsDto>empty();
        };

        walletAsyncQueueClient.ifPresent(
                queueClient -> wallet.flatMap(walletData -> tracingUtils.traceMono(
                                        this.getClass().getSimpleName(),
                                        (tracingInfo) -> queueClient.fireWalletLastUsageEvent(
                                                walletData.getWalletId(),
                                                transactionActivated.getClientId(),
                                                tracingInfo
                                        )
                                ).doOnError(
                                        exception -> log.error(
                                                "Failed to send event WALLET_USED for transactionId: [{}], wallet: [{}], clientId: [{}]",
                                                transactionActivated.getTransactionId(),
                                                walletData.getWalletId(),
                                                transactionActivated.getClientId(),
                                                exception
                                        )
                                )
                                .doOnNext(
                                        ignored -> log.info(
                                                "Send event WALLET_USED for transactionId: [{}], wallet: [{}], clientId: [{}]",
                                                transactionActivated.getTransactionId(),
                                                walletData.getWalletId(),
                                                transactionActivated.getClientId()
                                        )
                                ).then())
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe());
    }
}
