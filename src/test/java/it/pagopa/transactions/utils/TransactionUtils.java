package it.pagopa.transactions.utils;

import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.*;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithCompletedAuthorization;
import org.jetbrains.annotations.NotNull;

import java.time.ZonedDateTime;
import java.util.UUID;

public class TransactionUtils {
    public static final String RPT_ID = "77777777777111111111111111111";
    public static final String PAYMENT_TOKEN = "paymentToken";
    public static final String DESCRIPTION = "description";
    public static final int AMOUNT = 100;
    public static final String EMAIL = "foo@example.com";
    public static final String FAULT_CODE = "";
    public static final String FAULT_CODE_STRING = "";
    public static final String PAYMENT_INSTRUMENT_ID = "paymentInstrumentId";
    public static final String PSP_ID = "pspId";
    public static final String PAYMENT_TYPE_CODE = "paymentTypeCode";
    public static final String BROKER_NAME = "brokerName";
    public static final String PSP_CHANNEL_CODE = "pspChannelCode";
    public static final String PAYMENT_METHOD_NAME = "paymentMethodName";
    public static final String PSP_BUSINESS_NAME = "pspBusinessName";
    public static final String AUTHORIZATION_REQUEST_ID = "authorizationRequestId";
    public static final String TRANSACTION_ID = UUID.randomUUID().toString();

    @NotNull
    public static TransactionActivatedEvent transactionActivateEvent() {

        return new TransactionActivatedEvent(
                TRANSACTION_ID,
                RPT_ID,
                PAYMENT_TOKEN,
                new TransactionActivatedData(
                        DESCRIPTION,
                        AMOUNT,
                        EMAIL,
                        FAULT_CODE,
                        FAULT_CODE_STRING,
                        PAYMENT_TOKEN
                ));
    }

    @NotNull
    public static TransactionActivated transactionActivated(String creationDate) {
        return new TransactionActivated(
                new TransactionId(UUID.fromString(TRANSACTION_ID)),
                new PaymentToken(PAYMENT_TOKEN),
                new RptId(RPT_ID),
                new TransactionDescription(DESCRIPTION),
                new TransactionAmount(AMOUNT),
                new Email(EMAIL),
                FAULT_CODE,
                FAULT_CODE_STRING,
                ZonedDateTime.parse(creationDate),
                TransactionStatusDto.ACTIVATED
        );
    }

    @NotNull
    public static TransactionWithRequestedAuthorization transactionWithRequestedAuthorization(TransactionAuthorizationRequestedEvent authorizationRequestedEvent, TransactionActivated transactionActivated) {
        return new TransactionWithRequestedAuthorization(
                transactionActivated.withStatus(TransactionStatusDto.AUTHORIZATION_REQUESTED),
                authorizationRequestedEvent
        );
    }

    @NotNull
    public static TransactionAuthorizationRequestedEvent transactionAuthorizationRequestedEvent() {
        return new TransactionAuthorizationRequestedEvent(
                TRANSACTION_ID,
                RPT_ID,
                PAYMENT_TOKEN,
                new TransactionAuthorizationRequestData(
                        AMOUNT,
                        10,
                        PAYMENT_INSTRUMENT_ID,
                        PSP_ID,
                        PAYMENT_TYPE_CODE,
                        BROKER_NAME,
                        PSP_CHANNEL_CODE,
                        PAYMENT_METHOD_NAME,
                        PSP_BUSINESS_NAME,
                        AUTHORIZATION_REQUEST_ID
                )
        );
    }

    @NotNull
    public static TransactionAuthorizationStatusUpdatedEvent transactionAuthorizationStatusUpdatedEvent(AuthorizationResultDto authorizationResult) {
        TransactionStatusDto newStatus;
        switch (authorizationResult) {
            case OK -> newStatus = TransactionStatusDto.AUTHORIZED;
            case KO -> newStatus = TransactionStatusDto.AUTHORIZATION_FAILED;
            default -> throw new IllegalStateException("Unexpected value: " + authorizationResult);
        }

        return new TransactionAuthorizationStatusUpdatedEvent(
                TRANSACTION_ID,
                RPT_ID,
                PAYMENT_TOKEN,
                new TransactionAuthorizationStatusUpdateData(
                        authorizationResult,
                        newStatus
                )
        );
    }

    @NotNull
    public static TransactionWithCompletedAuthorization transactionWithCompletedAuthorization(TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent, TransactionWithRequestedAuthorization transactionWithRequestedAuthorization) {
        return new TransactionWithCompletedAuthorization(
                transactionWithRequestedAuthorization.withStatus(TransactionStatusDto.AUTHORIZED),
                authorizationStatusUpdatedEvent
        );
    }

    @NotNull
    public static TransactionClosureSentEvent transactionClosureSentEvent(ClosePaymentResponseDto.OutcomeEnum closePaymentOutcome) {
        TransactionStatusDto newStatus;
        switch (closePaymentOutcome) {
            case OK -> newStatus = TransactionStatusDto.CLOSED;
            case KO -> newStatus = TransactionStatusDto.CLOSURE_FAILED;
            default -> throw new IllegalStateException("Unexpected value: " + closePaymentOutcome);
        }

        return new TransactionClosureSentEvent(
                TRANSACTION_ID,
                RPT_ID,
                PAYMENT_TOKEN,
                new TransactionClosureSendData(
                        closePaymentOutcome,
                        newStatus,
                        "authorizationCode"
                )
        );
    }

    @NotNull
    public static TransactionClosureErrorEvent transactionClosureErrorEvent() {
        return new TransactionClosureErrorEvent(
                TRANSACTION_ID,
                RPT_ID,
                PAYMENT_TOKEN
        );
    }

    @NotNull
    public static TransactionWithClosureError transactionWithClosureError(TransactionClosureErrorEvent transactionClosureErrorEvent, TransactionWithCompletedAuthorization transaction) {
        return new TransactionWithClosureError(transaction.withStatus(TransactionStatusDto.CLOSURE_ERROR), transactionClosureErrorEvent);
    }

    @NotNull
    public static TransactionClosed transactionClosed(TransactionClosureSentEvent closureSentEvent, BaseTransactionWithCompletedAuthorization transactionWithCompletedAuthorization) {
        TransactionStatusDto newStatus = closureSentEvent.getData().getNewTransactionStatus();

        return new TransactionClosed(((BaseTransactionWithCompletedAuthorization) transactionWithCompletedAuthorization.withStatus(newStatus)), closureSentEvent);
    }

    @NotNull
    public static TransactionActivationRequestedEvent transactionActivationRequestedEvent() {
        return new TransactionActivationRequestedEvent(
                TRANSACTION_ID,
                RPT_ID,
                ZonedDateTime.now().toString(),
                new TransactionActivationRequestedData(
                        DESCRIPTION,
                        AMOUNT,
                        EMAIL,
                        FAULT_CODE,
                        FAULT_CODE_STRING,
                        "paymentContextCode"
                )
        );
    }
}
