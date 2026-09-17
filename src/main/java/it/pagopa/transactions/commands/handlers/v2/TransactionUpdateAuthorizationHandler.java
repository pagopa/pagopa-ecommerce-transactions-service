package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionEventCode;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransactionWithRequestedAuthorization;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto;
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.generated.wallet.v1.dto.WalletNotificationRequestCardDetailsDto;
import it.pagopa.generated.wallet.v1.dto.WalletNotificationRequestDto;
import it.pagopa.transactions.client.WalletClient;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.handlers.TransactionUpdateAuthorizationHandlerCommon;
import it.pagopa.transactions.configurations.WalletConfig;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.WalletErrorResponseException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component(TransactionUpdateAuthorizationHandler.QUALIFIER_NAME)
@Slf4j
public class TransactionUpdateAuthorizationHandler extends TransactionUpdateAuthorizationHandlerCommon
        implements ApplicationListener<ApplicationReadyEvent> {

    public static final String QUALIFIER_NAME = "transactionUpdateAuthorizationHandlerV2";
    private final TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData> transactionEventStoreRepository;
    private final WalletClient walletClient;
    private final Sinks.Many<TransactionUpdateAuthorizationCommand> authorizationCommandsSink = Sinks.many().unicast()
            .onBackpressureBuffer();
    private final WalletConfig walletConfig;

    @Autowired
    protected TransactionUpdateAuthorizationHandler(
            TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository,
            AuthRequestDataUtils extractAuthRequestData,
            TransactionsUtils transactionsUtils,
            WalletClient walletClient,
            WalletConfig walletConfig
    ) {
        super(extractAuthRequestData, transactionsUtils);
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.walletClient = walletClient;
        this.walletConfig = walletConfig;
    }

    @Override
    public void onApplicationEvent(@NotNull ApplicationReadyEvent event) {
        subscribeToAuthorizationCommandSink();
    }

    public void subscribeToAuthorizationCommandSink() {
        authorizationCommandsSink
                .asFlux()
                .doOnSubscribe(s -> {
                    if (log.isDebugEnabled()) {
                        LogTracingUtils.loggerTracingUtils()
                                .success()
                                .details(
                                        Map.of(
                                                "reactive_streams_subscription",
                                                s.toString()
                                        )
                                )
                                .logDebug(log, "Subscribed to authorization command sink");
                    }
                }
                )
                .flatMap(
                        command -> notifyWalletForContextualOnboarding(command)
                                .retryWhen(
                                        Retry.backoff(
                                                walletConfig.notification().maxRetryAttempts(),
                                                Duration.ofSeconds(
                                                        walletConfig.notification()
                                                                .exponentialBackoffRetryOffsetSeconds()
                                                )
                                        )
                                                .filter(
                                                        exception -> !(exception instanceof WalletErrorResponseException walletErrorResponseException
                                                                && walletErrorResponseException.getHttpStatus()
                                                                        .is4xxClientError())
                                                )
                                                .doBeforeRetry(
                                                        signal -> LogTracingUtils.loggerTracingUtils()
                                                                .failure()
                                                                .dependency(LogTracingUtils.WALLET_DEPENDENCY)
                                                                .details(
                                                                        Map.of(
                                                                                "wallet_id",
                                                                                extractWalletInfo(command)
                                                                                        .map(WalletInfo::getWalletId)
                                                                                        .orElse("{walletId-not-found}")
                                                                        )
                                                                )
                                                                .logError(
                                                                        log,
                                                                        signal.failure(),
                                                                        "Exception performing POST wallet notification"
                                                                )
                                                )
                                )
                                .onErrorResume(exception -> {
                                    LogTracingUtils.loggerTracingUtils()
                                            .failure()
                                            .dependency(LogTracingUtils.WALLET_DEPENDENCY)
                                            .details(
                                                    Map.of(
                                                            "wallet_id",
                                                            extractWalletInfo(command)
                                                                    .map(WalletInfo::getWalletId)
                                                                    .orElse("{walletId-not-found}")
                                                    )
                                            )
                                            .logError(
                                                    log,
                                                    exception,
                                                    "Error performing POST wallet notification, wallet status may have not been updated correctly!"
                                            );
                                    return Mono.empty();
                                })
                )
                .doOnNext(
                        TupleUtils.consumer(
                                (
                                 walletInfo,
                                 walletNotificationRequest
                                ) -> LogTracingUtils.loggerTracingUtils()
                                        .success()
                                        .dependency(LogTracingUtils.WALLET_DEPENDENCY)
                                        .details(
                                                Map.of(
                                                        "wallet_id",
                                                        walletInfo.getWalletId(),
                                                        "npg_operation_id",
                                                        Objects.toString(walletNotificationRequest.getOperationId()),
                                                        "npg_operation_result",
                                                        Objects.toString(walletNotificationRequest.getOperationResult())
                                                )
                                        )
                                        .logInfo(log, "Post wallet notification performed successfully")
                        )
                )
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    @Override
    public Mono<BaseTransactionEvent<?>> handle(TransactionUpdateAuthorizationCommand command) {
        TransactionId transactionId = command.getData().transactionId();
        Mono<it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction> transaction = transactionsUtils
                .reduceV2Events(
                    command.getEvents()
                );

        Mono<BaseTransactionEvent<?>> alreadyProcessedError = transaction.flatMap(tx ->
                Mono.error(
                        AlreadyProcessedException.builder()
                                .transactionId(transactionId)
                                .transactionStatus(tx.getStatus().toString())
                                .build()

                )
        );
        UpdateAuthorizationRequestDto updateAuthorizationRequest = command.getData().updateAuthorizationRequest();
        AuthRequestDataUtils.AuthRequestData authRequestDataExtracted = extractAuthRequestData
                .from(updateAuthorizationRequest, transactionId);
        TransactionStatusDto transactionStatus = TransactionStatusDto.valueOf(command.getData().transactionStatus());

        if (transactionStatus.equals(TransactionStatusDto.AUTHORIZATION_REQUESTED)) {
            UpdateAuthorizationRequestOutcomeGatewayDto outcomeGateway = command.getData().updateAuthorizationRequest()
                    .getOutcomeGateway();

            TransactionGatewayAuthorizationData authorizationData =
                    switch (outcomeGateway) {
                        case OutcomeNpgGatewayDto outcomeNpgGateway -> new NpgTransactionGatewayAuthorizationData(
                                OperationResultDto.valueOf(outcomeNpgGateway.getOperationResult().toString()),
                                outcomeNpgGateway.getOperationId(),
                                outcomeNpgGateway.getPaymentEndToEndId(),
                                authRequestDataExtracted.errorCode(),
                                outcomeNpgGateway.getValidationServiceId()
                        );
                        case OutcomeRedirectGatewayDto outcomeRedirectGatewayDto ->
                                new RedirectTransactionGatewayAuthorizationData(
                                        RedirectTransactionGatewayAuthorizationData.Outcome.valueOf(outcomeRedirectGatewayDto.getOutcome().toString()),
                                        authRequestDataExtracted.errorCode()

                                );
                        default -> throw new InvalidRequestException("Unexpected value: " + outcomeGateway);
                    };
            return Mono.just(command)
                    .flatMap(authCommand ->
                            Mono.fromRunnable(() -> authorizationCommandsSink
                                            .emitNext(
                                                    authCommand,
                                                    Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(500))
                                            )
                                    ).thenReturn(authCommand)
                                    .doOnNext(ignored -> {
                                            if (log.isDebugEnabled()) {
                                                LogTracingUtils.loggerTracingUtils()
                                                        .success()
                                                        .details(
                                                                Map.of(
                                                                        "wallet_id", extractWalletInfo(authCommand)
                                                                                .map(WalletInfo::getWalletId)
                                                                                .orElse("{walletId-not-found}")
                                                                )
                                                        )
                                                        .logDebug(log, "POST wallet notification event emitted successfully");
                                            }
                                        }
                                    )
                                    .doOnError(exception ->
                                            LogTracingUtils.loggerTracingUtils()
                                                            .failure()
                                                            .details(
                                                                    Map.of(
                                                                            "wallet_id", extractWalletInfo(authCommand)
                                                                                    .map(WalletInfo::getWalletId)
                                                                                    .orElse("{walletId-not-found}")
                                                                    )
                                                            )
                                                            .logError(log, exception, "Exception emitting event for POST wallet notification")
                                    )
                                    .onErrorReturn(authCommand)
                    )
                    .thenReturn(
                            new it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedEvent(
                                    transactionId.value(),
                                    new it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData(
                                            authRequestDataExtracted.authorizationCode(),
                                            authRequestDataExtracted.rrn(),
                                            updateAuthorizationRequest.getTimestampOperation().toString(),
                                            authorizationData
                                    )
                            )
                    )
                    .flatMap(event ->
                            transactionEventStoreRepository.insert(event)
                                    .doOnNext(e ->
                                            LogTracingUtils.loggerTracingUtils()
                                                    .success()
                                                    .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                                                    .attributes(
                                                            Map.of(
                                                                    LogTracingUtils.AttributeKeys.CTX_EVENT_CODE, e.getEventCode()
                                                            )
                                                    )
                                                    .logInfo(log, "Saved domain event")
                                    )
                    );
        } else {
            return alreadyProcessedError;
        }

    }

    private Mono<Tuple2<WalletInfo, WalletNotificationRequestDto>> notifyWalletForContextualOnboarding(
                                                                                                       TransactionUpdateAuthorizationCommand command
    ) {
        Mono<BaseTransaction> transaction = transactionsUtils.reduceV2Events(command.getEvents());
        UpdateAuthorizationRequestDto updateAuthRequest = command.getData().updateAuthorizationRequest();
        return transaction
                .cast(BaseTransactionWithRequestedAuthorization.class)
                .filter(this::isNpgCardPaymentWithContextualOnboarding)
                .flatMap(tx -> {
                    WalletInfo walletInfo = extractWalletInfo(command)
                            .orElseThrow(
                                    () -> new RuntimeException(
                                            "Null wallet info not valid for payment with contextual onboarding"
                                    )
                            );
                    String walletId = walletInfo.getWalletId();
                    OutcomeNpgGatewayDto outcomeNpgGatewayDto = (OutcomeNpgGatewayDto) updateAuthRequest
                            .getOutcomeGateway();
                    String orderId = outcomeNpgGatewayDto.getOrderId();
                    WalletNotificationRequestDto request = new WalletNotificationRequestDto()
                            .timestampOperation(updateAuthRequest.getTimestampOperation())
                            .operationId(outcomeNpgGatewayDto.getOperationId())
                            .operationResult(
                                    WalletNotificationRequestDto.OperationResultEnum
                                            .fromValue(outcomeNpgGatewayDto.getOperationResult().toString())
                            )
                            .errorCode(outcomeNpgGatewayDto.getErrorCode())
                            .details(
                                    // payment with contextual onboarding supported only for CARD method
                                    new WalletNotificationRequestCardDetailsDto()
                                            .paymentInstrumentGatewayId(
                                                    Objects.requireNonNull(
                                                            outcomeNpgGatewayDto.getCardId4(),
                                                            "null cardId4 NPG field not valid!"
                                                    )
                                            )
                                            .type("CARD")
                            );
                    return walletClient.notifyWallet(
                            walletId,
                            orderId,
                            request
                    ).thenReturn(Tuples.of(walletInfo, request));
                });
    }

    private Optional<WalletInfo> extractWalletInfo(
                                                   TransactionUpdateAuthorizationCommand command
    ) {
        return command.getEvents()
                .stream()
                .filter(
                        event -> event.getEventCode()
                                .equals(TransactionEventCode.TRANSACTION_AUTHORIZATION_REQUESTED_EVENT.toString())
                )
                .map(event -> {
                    if (event.getData()instanceof TransactionAuthorizationRequestData data) {
                        if (data.getTransactionGatewayAuthorizationRequestedData()instanceof NpgTransactionGatewayAuthorizationRequestedData d) {
                            return Optional.ofNullable(d.getWalletInfo());
                        } else {
                            return Optional.<WalletInfo>empty();
                        }
                    } else {
                        return Optional.<WalletInfo>empty();
                    }
                })
                .flatMap(Optional::stream)
                .findFirst();
    }

    private boolean isNpgCardPaymentWithContextualOnboarding(BaseTransactionWithRequestedAuthorization transaction) {
        // payment with contextual onboarding performable only for NPG cards wallet
        // methods
        boolean isContextualOnboarding = Boolean.TRUE
                .equals(transaction.getTransactionAuthorizationRequestData().getIsContextualOnboard());
        TransactionAuthorizationRequestData authorizationRequestedData = transaction
                .getTransactionAuthorizationRequestData();
        TransactionGatewayAuthorizationRequestedData gatewayData = authorizationRequestedData
                .getTransactionGatewayAuthorizationRequestedData();
        boolean isNpgTransaction = gatewayData instanceof NpgTransactionGatewayAuthorizationRequestedData;
        boolean isWalletPayment = isNpgTransaction
                && ((NpgTransactionGatewayAuthorizationRequestedData) gatewayData).getWalletInfo() != null;
        boolean isCardPayment = authorizationRequestedData.getPaymentTypeCode().equals("CP");
        boolean isNpgWithContextualOnboarding = isContextualOnboarding && isWalletPayment && isCardPayment;
        LogTracingUtils.loggerTracingUtils()
                .success()
                .details(
                        Map.of(
                                "is_npg_with_contextual_onboarding",
                                String.valueOf(isNpgWithContextualOnboarding),
                                "is_contextual_onboarding",
                                String.valueOf(isContextualOnboarding),
                                "is_wallet_payment",
                                String.valueOf(isWalletPayment),
                                "is_card_payment",
                                String.valueOf(isCardPayment)
                        )
                )
                .logInfo(log, "Transaction payment method verified");
        return isNpgWithContextualOnboarding;
    }
}
