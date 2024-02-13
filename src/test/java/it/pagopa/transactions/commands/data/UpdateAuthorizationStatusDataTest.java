package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.AuthorizationOutcomeDto;
import it.pagopa.generated.transactions.server.model.OutcomeRedirectGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdateAuthorizationStatusDataTest {

    @Test
    void shouldReturnErrorHandlingRedirectAuthUpdateRequestWithMismatchPspId() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent(
                        TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
                        TransactionTestUtils.redirectTransactionGatewayAuthorizationRequestedData()
                );
        String errorCode = "errorCode";

        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeRedirectGatewayDto()
                                .outcome(AuthorizationOutcomeDto.OK)
                                .paymentGatewayType("REDIRECT")
                                .errorCode(errorCode)
                                .authorizationCode(TransactionTestUtils.AUTHORIZATION_CODE)
                                .pspTransactionId(TransactionTestUtils.REDIRECT_PSP_TRANSACTION_ID)
                                .pspId("invalid")
                )
                .timestampOperation(OffsetDateTime.now());

        /* test */
        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> new UpdateAuthorizationStatusData(
                        transaction.getTransactionId(),
                        transaction.getStatus().toString(),
                        updateAuthorizationRequest,
                        ZonedDateTime.now(),
                        Optional.of(transaction)
                )
        );

        assertEquals(
                "Invalid update auth redirect request received! Validation error: psp id mismatch",
                exception.getMessage()
        );
    }

    @Test
    void shouldReturnErrorHandlingRedirectAuthUpdateRequestWithMismatchPspTransactionId() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent(
                        TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
                        TransactionTestUtils.redirectTransactionGatewayAuthorizationRequestedData()
                );
        String errorCode = "errorCode";

        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeRedirectGatewayDto()
                                .outcome(AuthorizationOutcomeDto.OK)
                                .paymentGatewayType("REDIRECT")
                                .errorCode(errorCode)
                                .authorizationCode(TransactionTestUtils.AUTHORIZATION_CODE)
                                .pspTransactionId("Invalid")
                                .pspId(TransactionTestUtils.PSP_ID)
                )
                .timestampOperation(OffsetDateTime.now());

        /* preconditions */
        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> new UpdateAuthorizationStatusData(
                        transaction.getTransactionId(),
                        transaction.getStatus().toString(),
                        updateAuthorizationRequest,
                        ZonedDateTime.now(),
                        Optional.of(transaction)
                )
        );

        assertEquals(
                "Invalid update auth redirect request received! Validation error: psp transaction id mismatch",
                exception.getMessage()
        );
    }

    @Test
    void shouldReturnErrorHandlingRedirectAuthUpdateRequestReceivedAfterTimeout() {
        int authorizationTimeoutMillis = 600000;
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent(
                        TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
                        new RedirectTransactionGatewayAuthorizationRequestedData(
                                TransactionTestUtils.LOGO_URI,
                                TransactionTestUtils.REDIRECT_PSP_TRANSACTION_ID,
                                authorizationTimeoutMillis,
                                TransactionTestUtils.REDIRECT_AUTHORIZATION_PAYMENT_METHOD
                        )
                );
        // set authorization requested event to be performed at
        // authorizationTimeoutMillis timeout +1 milliseconds so that
        // authorization completed timestamp evaluation will fail because of too late
        // received authorization request
        ZonedDateTime authorizationRequestedEventTimestamp = ZonedDateTime.now()
                .minus(Duration.ofMillis(authorizationTimeoutMillis + 1));
        authorizationRequestedEvent.setCreationDate(
                authorizationRequestedEventTimestamp.toString()
        );
        String errorCode = "errorCode";
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeRedirectGatewayDto()
                                .outcome(AuthorizationOutcomeDto.OK)
                                .paymentGatewayType("REDIRECT")
                                .errorCode(errorCode)
                                .authorizationCode(TransactionTestUtils.AUTHORIZATION_CODE)
                                .pspTransactionId(TransactionTestUtils.REDIRECT_PSP_TRANSACTION_ID)
                                .pspId(TransactionTestUtils.PSP_ID)
                )
                .timestampOperation(OffsetDateTime.now());

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> new UpdateAuthorizationStatusData(
                        transaction.getTransactionId(),
                        transaction.getStatus().toString(),
                        updateAuthorizationRequest,
                        authorizationRequestedEventTimestamp,
                        Optional.of(transaction)
                )
        );

        assertEquals(
                "Invalid update auth redirect request received! Validation error: authorization outcome received after threshold",
                exception.getMessage()
        );

    }

    @Test
    void shouldReturnErrorHandlingRedirectAuthUpdateRequestWithInvalidEventData() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent(
                        TransactionTestUtils.npgTransactionGatewayAuthorizationRequestedData()
                );
        String errorCode = "errorCode";
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeRedirectGatewayDto()
                                .outcome(AuthorizationOutcomeDto.OK)
                                .paymentGatewayType("REDIRECT")
                                .errorCode(errorCode)
                                .authorizationCode(TransactionTestUtils.AUTHORIZATION_CODE)
                                .pspTransactionId("Invalid")
                                .pspId(TransactionTestUtils.PSP_ID)
                )
                .timestampOperation(OffsetDateTime.now());

        /* test */
        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> new UpdateAuthorizationStatusData(
                        transaction.getTransactionId(),
                        transaction.getStatus().toString(),
                        updateAuthorizationRequest,
                        ZonedDateTime.now(),
                        Optional.of(transaction)
                )
        );

        assertEquals(
                "Redirect update auth request received for transaction performed with gateway: [VPOS]",
                exception.getMessage()
        );

    }

    @Test
    void shouldReturnErrorHandlingRedirectAuthUpdateRequestWithMissingBaseTransaction() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent(
                        TransactionTestUtils.npgTransactionGatewayAuthorizationRequestedData()
                );
        String errorCode = "errorCode";
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeRedirectGatewayDto()
                                .outcome(AuthorizationOutcomeDto.OK)
                                .paymentGatewayType("REDIRECT")
                                .errorCode(errorCode)
                                .authorizationCode(TransactionTestUtils.AUTHORIZATION_CODE)
                                .pspTransactionId("Invalid")
                                .pspId(TransactionTestUtils.PSP_ID)
                )
                .timestampOperation(OffsetDateTime.now());

        /* test */
        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> new UpdateAuthorizationStatusData(
                        transaction.getTransactionId(),
                        transaction.getStatus().toString(),
                        updateAuthorizationRequest,
                        ZonedDateTime.now(),
                        Optional.empty()
                )
        );

        assertEquals(
                "Invalid update auth redirect request received! Validation error: missing base transaction, cannot perform validation checks",
                exception.getMessage()
        );

    }

    @Test
    void shouldReturnErrorHandlingRedirectAuthUpdateRequestWithWrongBaseTransaction() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        String errorCode = "errorCode";
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeRedirectGatewayDto()
                                .outcome(AuthorizationOutcomeDto.OK)
                                .paymentGatewayType("REDIRECT")
                                .errorCode(errorCode)
                                .authorizationCode(TransactionTestUtils.AUTHORIZATION_CODE)
                                .pspTransactionId("Invalid")
                                .pspId(TransactionTestUtils.PSP_ID)
                )
                .timestampOperation(OffsetDateTime.now());

        /* test */
        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> new UpdateAuthorizationStatusData(
                        transaction.getTransactionId(),
                        transaction.getStatus().toString(),
                        updateAuthorizationRequest,
                        ZonedDateTime.now(),
                        Optional.of(transaction)
                )
        );

        assertEquals(
                "Invalid update auth redirect request received! Validation error: invalid reduced base transaction: TransactionActivated()",
                exception.getMessage()
        );

    }

}
