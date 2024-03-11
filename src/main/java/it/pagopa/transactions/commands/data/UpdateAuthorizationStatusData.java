package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransactionWithRequestedAuthorization;
import it.pagopa.generated.transactions.server.model.OutcomeRedirectGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestOutcomeGatewayDto;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

public record UpdateAuthorizationStatusData(
        TransactionId transactionId,
        String transactionStatus,
        UpdateAuthorizationRequestDto updateAuthorizationRequest,

        ZonedDateTime authorizationRequestedTime,

        Optional<BaseTransaction> baseTransactionV2
) {

    private static final Logger logger = LoggerFactory.getLogger(UpdateAuthorizationStatusData.class);

    public UpdateAuthorizationStatusData {
        Objects.requireNonNull(transactionId);
        Objects.requireNonNull(transactionStatus);
        Objects.requireNonNull(updateAuthorizationRequest);
        Objects.requireNonNull(authorizationRequestedTime);
        Objects.requireNonNull(baseTransactionV2);
        validateUpdateTransactionRequestValid(
                updateAuthorizationRequest,
                authorizationRequestedTime,
                baseTransactionV2
        );

    }

    /**
     * This method performs validation check against received update authorization
     * request
     * <p>
     * error with request validation failure details
     *
     * @param updateAuthRequestDto the update authorization request DTo
     * @param authRequestedTime    authorization requested time
     * @param transactionV2
     */
    private void validateUpdateTransactionRequestValid(
                                                       UpdateAuthorizationRequestDto updateAuthRequestDto,
                                                       ZonedDateTime authRequestedTime,
                                                       Optional<BaseTransaction> transactionV2
    ) {

        UpdateAuthorizationRequestOutcomeGatewayDto outcomeGateway = updateAuthRequestDto.getOutcomeGateway();
        // update transaction status request validation for Redirect payment flows
        if (outcomeGateway instanceof OutcomeRedirectGatewayDto redirectGatewayDto) {
            String requestValidationErrorHeader = "Invalid update auth redirect request received! Validation error: %s";
            BaseTransaction tx = transactionV2.orElseThrow(
                    () -> new InvalidRequestException(
                            requestValidationErrorHeader
                                    .formatted("missing base transaction, cannot perform validation checks")
                    )
            );
            if (!(tx instanceof BaseTransactionWithRequestedAuthorization txWithRequestedAuth)) {
                throw new InvalidRequestException(
                        requestValidationErrorHeader.formatted("invalid reduced base transaction: " + tx)
                );
            }
            if (txWithRequestedAuth.getTransactionAuthorizationRequestData()
                    .getTransactionGatewayAuthorizationRequestedData()instanceof RedirectTransactionGatewayAuthorizationRequestedData authRequestedData) {

                String pspId = redirectGatewayDto.getPspId();
                String expectedPspId = txWithRequestedAuth.getTransactionAuthorizationRequestData().getPspId();
                long timeout = authRequestedData.getTransactionOutcomeTimeoutMillis();
                if (!pspId.equals(expectedPspId)) {
                    logger.error(
                            "Invalid redirect authorization outcome psp id received. Expected: [{}], received: [{}]",
                            expectedPspId,
                            pspId
                    );
                    throw new InvalidRequestException(
                            requestValidationErrorHeader.formatted("psp id mismatch")
                    );
                }

                Instant authRequestedInstant = authRequestedTime.toInstant();
                Instant authCompletedThreshold = authRequestedInstant.plus(Duration.ofMillis(timeout));
                Instant now = Instant.now();
                boolean isOnTime = !now.isAfter(authCompletedThreshold);
                logger.info(
                        "Redirect authorization outcome received at: [{}]. Authorization requested at: [{}], psp received timeout: [{}] -> is on time: [{}]",
                        now,
                        authRequestedInstant,
                        timeout,
                        isOnTime
                );
                if (!isOnTime) {

                    throw new InvalidRequestException(
                            requestValidationErrorHeader
                                    .formatted("authorization outcome received after threshold")
                    );
                }

            } else {
                throw new InvalidRequestException(
                        "Redirect update auth request received for transaction performed with gateway: [%s]"
                                .formatted(
                                        txWithRequestedAuth.getTransactionAuthorizationRequestData()
                                                .getPaymentGateway()
                                )
                );
            }

        }
    }
}
