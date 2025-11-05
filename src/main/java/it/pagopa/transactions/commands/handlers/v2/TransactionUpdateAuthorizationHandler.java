package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransactionWithRequestedAuthorization;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto;
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
import java.util.Objects;

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
                .doOnSubscribe(s -> log.debug("Subscribed to authorization command sink with subscription: {}", s))
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
                                                        signal -> log.warn(
                                                                "Exception performing POST wallet notification",
                                                                signal.failure()
                                                        )
                                                )
                                )
                )

                .doOnNext(
                        TupleUtils.consumer(
                                (
                                 walletInfo,
                                 walletNotificationRequest
                                ) -> log.info(
                                        "Post wallet performed successfully for walletId: [{}], with NPG operationResult: [{}]",
                                        walletInfo.getWalletId(),
                                        walletNotificationRequest.getOperationResult()
                                )
                        )
                )
                .doOnError(
                        exception -> log.error(
                                "Error performing POST wallet notification, wallet status may have not been updated correctly!",
                                exception
                        )
                )
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    @Override
    public Mono<BaseTransactionEvent<?>> handle(TransactionUpdateAuthorizationCommand command) {
        TransactionId transactionId = command.getData().transactionId();
        Mono<BaseTransactionEvent<?>> alreadyProcessedError = Mono.error(new AlreadyProcessedException(transactionId));
        UpdateAuthorizationRequestDto updateAuthorizationRequest = command.getData().updateAuthorizationRequest();
        AuthRequestDataUtils.AuthRequestData authRequestDataExtracted = extractAuthRequestData
                .from(updateAuthorizationRequest, transactionId);
        TransactionStatusDto transactionStatus = TransactionStatusDto.valueOf(command.getData().transactionStatus());

        if (transactionStatus.equals(TransactionStatusDto.AUTHORIZATION_REQUESTED)) {
            Sinks.EmitResult result = authorizationCommandsSink.tryEmitNext(command);
            log.debug("Emit command result -> [{}]", result);
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
            return Mono.just(
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
                    .flatMap(transactionEventStoreRepository::save);
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
                    NpgTransactionGatewayAuthorizationRequestedData npgAuthRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) tx
                            .getTransactionAuthorizationRequestData().getTransactionGatewayAuthorizationRequestedData();
                    WalletInfo walletInfo = Objects.requireNonNull(
                            npgAuthRequestedData.getWalletInfo(),
                            "Null wallet info not valid for payment with contextual onboarding"
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
        return isContextualOnboarding && isWalletPayment && isCardPayment;
    }

}
