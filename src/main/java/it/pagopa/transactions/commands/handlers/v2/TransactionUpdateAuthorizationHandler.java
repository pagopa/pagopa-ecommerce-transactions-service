package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.*;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.domain.v2.TransactionWithRequestedAuthorization;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.handlers.TransactionUpdateAuthorizationHandlerCommon;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component(TransactionUpdateAuthorizationHandler.QUALIFIER_NAME)
@Slf4j
public class TransactionUpdateAuthorizationHandler extends TransactionUpdateAuthorizationHandlerCommon {

    public static final String QUALIFIER_NAME = "TransactionUpdateAuthorizationHandlerV2";
    private final TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData> transactionEventStoreRepository;

    private final Map<String, URI> npgPaymentCircuitLogoMap;

    @Autowired
    protected TransactionUpdateAuthorizationHandler(
            TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository,
            AuthRequestDataUtils extractAuthRequestData,
            TransactionsUtils transactionsUtils,
            @Qualifier("npgPaymentCircuitLogoMap") Map<String, URI> npgPaymentCircuitLogoMap
    ) {
        super(extractAuthRequestData, transactionsUtils);
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.npgPaymentCircuitLogoMap = npgPaymentCircuitLogoMap;
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
            UpdateAuthorizationRequestOutcomeGatewayDto outcomeGateway = command.getData().updateAuthorizationRequest()
                    .getOutcomeGateway();

            TransactionGatewayAuthorizationData authorizationData =
                    switch (outcomeGateway) {
                        case OutcomeNpgGatewayDto outcomeNpgGateway -> new NpgTransactionGatewayAuthorizationData(
                                OperationResultDto.valueOf(outcomeNpgGateway.getOperationResult().toString()),
                                outcomeNpgGateway.getOperationId(),
                                outcomeNpgGateway.getPaymentEndToEndId()
                        );
                        case OutcomeXpayGatewayDto ignored -> new PgsTransactionGatewayAuthorizationData(
                                authRequestDataExtracted.errorCode(),
                                AuthorizationResultDto
                                        .fromValue(
                                                authRequestDataExtracted.outcome()
                                        )
                        );
                        case OutcomeVposGatewayDto ignored -> new PgsTransactionGatewayAuthorizationData(
                                authRequestDataExtracted.errorCode(),
                                AuthorizationResultDto
                                        .fromValue(
                                                authRequestDataExtracted.outcome()
                                        )
                        );
                        case OutcomeRedirectGatewayDto outcomeRedirectGatewayDto ->
                                new RedirectTransactionGatewayAuthorizationData(
                                        RedirectTransactionGatewayAuthorizationData.Outcome.valueOf(outcomeRedirectGatewayDto.getOutcome().toString()),
                                        authRequestDataExtracted.errorCode()

                                );
                        default -> throw new IllegalStateException("Unexpected value: " + outcomeGateway);
                    };

            return isUpdateTransactionRequestValid(command)
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
                    .flatMap(transactionEventStoreRepository::save);
        } else {
            return alreadyProcessedError;
        }

    }

    /**
     * This method performs validation check against received update authorization
     * request
     *
     * @param command the handler command
     * @return an empty mono if request was validated successfully otherwise a mono
     *         error with request validation failure details
     */
    private Mono<Void> isUpdateTransactionRequestValid(TransactionUpdateAuthorizationCommand command) {
        TransactionId transactionId = command.getData().transactionId();
        UpdateAuthorizationRequestOutcomeGatewayDto outcomeGateway = command.getData().updateAuthorizationRequest()
                .getOutcomeGateway();
        Mono<Void> updateAuthorizationTimeoutCheck = Mono.empty();
        // update transaction status request validation for Redirect payment flows
        if (outcomeGateway instanceof OutcomeRedirectGatewayDto redirectGatewayDto) {
            updateAuthorizationTimeoutCheck = transactionsUtils
                    .reduceEventsV2(transactionId)
                    .cast(TransactionWithRequestedAuthorization.class)
                    .flatMap(tx -> {
                        if (tx.getTransactionAuthorizationRequestData()
                                .getTransactionGatewayAuthorizationRequestedData()instanceof RedirectTransactionGatewayAuthorizationRequestedData authRequestedData) {
                            String requestValidationErrorHeader = "Invalid update auth redirect request received! Validation error: %s";
                            String pspId = redirectGatewayDto.getPspId();
                            String expectedPspId = tx.getTransactionAuthorizationRequestData().getPspId();
                            String pspTransactionId = redirectGatewayDto.getPspTransactionId();
                            String expectedPspTransactionId = authRequestedData.getPspTransactionId();
                            long timeout = authRequestedData.getTransactionOutcomeTimeoutMillis();
                            if (!pspId.equals(expectedPspId)) {
                                log.error(
                                        "Invalid redirect authorization outcome psp id received. Expected: [{}], received: [{}]",
                                        expectedPspId,
                                        pspId
                                );
                                Mono.error(
                                        new InvalidRequestException(
                                                requestValidationErrorHeader.formatted("psp id mismatch")
                                        )
                                );
                            }
                            if (!pspTransactionId.equals(expectedPspTransactionId)) {
                                log.error(
                                        "Invalid redirect authorization outcome psp transaction id received. Expected: [{}], received: [{}]",
                                        expectedPspTransactionId,
                                        pspTransactionId
                                );
                                Mono.error(
                                        new InvalidRequestException(
                                                requestValidationErrorHeader.formatted("psp transaction id mismatch")
                                        )
                                );
                            }
                            Instant authRequestedInstant = command.getData().authorizationRequestedTime().toInstant();
                            Instant authCompletedThreshold = authRequestedInstant.plus(Duration.ofMillis(timeout));
                            if (Instant.now().isAfter(authCompletedThreshold)) {
                                log.error(
                                        "Redirect authorization outcome received after timeout. Authorization requested at: [{}], psp received timeout: [{}] -> authorization update outcome threshold: [{}]",
                                        authRequestedInstant,
                                        timeout,
                                        authCompletedThreshold
                                );
                                Mono.error(
                                        new InvalidRequestException(
                                                requestValidationErrorHeader
                                                        .formatted("authorization outcome received after threshold")
                                        )
                                );
                            }
                            return Mono.empty();
                        } else {
                            return Mono.error(
                                    new InvalidRequestException(
                                            "Redirect update auth request received for transaction performed with gateway: [%s]"
                                                    .formatted(
                                                            tx.getTransactionAuthorizationRequestData()
                                                                    .getPaymentGateway()
                                                    )
                                    )
                            );
                        }
                    });

        }
        return updateAuthorizationTimeoutCheck;
    }
}
