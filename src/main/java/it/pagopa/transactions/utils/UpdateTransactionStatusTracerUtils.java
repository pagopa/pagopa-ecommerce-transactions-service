package it.pagopa.transactions.utils;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * This utility class traces transaction update status performed by external
 * entities. Tracing is performed by meaning of OpenTelemetry span creation.
 * {@link UpdateTransactionStatusTracerUtils.UpdateTransactionStatusType}
 * enumeration contains transaction status typologies enumeration
 * {@link UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger}
 * enumeration, instead, contains external actors that trigger the transaction
 * status update
 */
@Component
public class UpdateTransactionStatusTracerUtils {

    private final OpenTelemetryUtils openTelemetryUtils;

    /**
     * Span attribute used to discriminate transaction update status operation type
     *
     * @see UpdateTransactionStatusType
     */
    static final AttributeKey<String> UPDATE_TRANSACTION_STATUS_TYPE_ATTRIBUTE_KEY = AttributeKey
            .stringKey("updateTransactionStatus.type");
    /**
     * Span attribute used to trace status update outcome
     *
     * @see UpdateTransactionStatusOutcome
     */
    static final AttributeKey<String> UPDATE_TRANSACTION_STATUS_OUTCOME_ATTRIBUTE_KEY = AttributeKey
            .stringKey("updateTransactionStatus.outcome");
    /**
     * Span attribute used to trace status update trigger
     *
     * @see UpdateTransactionTrigger
     */
    static final AttributeKey<String> UPDATE_TRANSACTION_STATUS_TRIGGER_ATTRIBUTE_KEY = AttributeKey
            .stringKey("updateTransactionStatus.trigger");

    /**
     * Span attribute used to trace transaction psp id (useful to discriminate
     * redirection payment flows
     */
    static final AttributeKey<String> UPDATE_TRANSACTION_STATUS_PSP_ID_ATTRIBUTE_KEY = AttributeKey
            .stringKey("updateTransactionStatus.pspId");

    /**
     * Enumeration of all operation that update transaction status performed by
     * external entities
     */
    public enum UpdateTransactionStatusType {
        /**
         * Transaction status update operation triggered by payment gateway by receiving
         * authorization outcome
         */
        AUTHORIZATION_OUTCOME,
        /**
         * Transaction status update operation triggered by Nodo with sendPaymentResult
         * operation
         */
        SEND_PAYMENT_RESULT_OUTCOME
    }

    /**
     * Enumeration of all actors that can trigger a transaction status update
     */
    public enum UpdateTransactionTrigger {
        /**
         * Transaction status update triggered by Nodo
         */
        NODO,
        /**
         * Transaction status update triggered by NPG (authorization outcome)
         */
        NPG,
        /**
         * Transaction status update triggered by PGS (VPOS)
         */
        PGS_VPOS,

        /**
         * Transaction status update triggered by PGS (XPAY)
         */
        PGS_XPAY,

        /**
         * Transaction status update triggered by PSP (through redirect payment flow
         * integration)
         */
        REDIRECT,

        /**
         * Used when cannot derive transaction status update trigger
         */
        UNKNOWN
    }

    /**
     * Enumeration of update transaction status possible outcomes
     */
    public enum UpdateTransactionStatusOutcome {
        /**
         * The transaction is status update has been processed successfully
         */
        OK,
        /**
         * Error processing transaction status update: transaction is in wrong state
         */
        WRONG_TRANSACTION_STATUS,

        /**
         * Error processing transaction status update: cannot found transaction for
         * input transaction id
         */
        TRANSACTION_NOT_FOUND,

        /**
         * Error processing transaction status update: the input request is invalid
         */
        INVALID_REQUEST,

        /**
         * Error processing transaction status update: an unexpected error has occurred
         * processing transaction update state
         */
        PROCESSING_ERROR
    }

    static final String UPDATE_TRANSACTION_STATUS_SPAN_NAME = "Transaction status updated";

    @Autowired
    public UpdateTransactionStatusTracerUtils(OpenTelemetryUtils openTelemetryUtils) {
        this.openTelemetryUtils = openTelemetryUtils;
    }

    /**
     * Trace status update operation for the input tracing information
     *
     * @param statusUpdateInfo - transaction status update information
     */
    public void traceStatusUpdateOperation(StatusUpdateInfo statusUpdateInfo) {
        Attributes spanAttributes = Attributes.of(
                UPDATE_TRANSACTION_STATUS_TYPE_ATTRIBUTE_KEY,
                statusUpdateInfo.type().toString(),
                UPDATE_TRANSACTION_STATUS_OUTCOME_ATTRIBUTE_KEY,
                statusUpdateInfo.outcome().toString(),
                UPDATE_TRANSACTION_STATUS_TRIGGER_ATTRIBUTE_KEY,
                statusUpdateInfo.trigger().toString(),
                UPDATE_TRANSACTION_STATUS_PSP_ID_ATTRIBUTE_KEY,
                statusUpdateInfo.pspId().orElse("N/A")
        );
        openTelemetryUtils.addSpanWithAttributes(UPDATE_TRANSACTION_STATUS_SPAN_NAME, spanAttributes);
    }

    /**
     * Transaction status update record for Nodo update trigger
     *
     * @param outcome - the transaction update outcome
     */
    public record NodoStatusUpdate(UpdateTransactionStatusOutcome outcome)
            implements
            StatusUpdateInfo {
        public NodoStatusUpdate {
            Objects.requireNonNull(outcome);
        }

        @Override
        public UpdateTransactionStatusType type() {
            return UpdateTransactionStatusType.SEND_PAYMENT_RESULT_OUTCOME;
        }

        @Override
        public UpdateTransactionTrigger trigger() {
            return UpdateTransactionTrigger.NODO;
        }

        @Override
        public Optional<String> pspId() {
            return Optional.empty();
        }

    }

    /**
     * Transaction status update record for payment transaction gateway update
     * trigger
     *
     * @param outcome - the transaction update status outcome
     * @param trigger - the gateway trigger that initiate the request
     * @param pspId   - the psp id chosen for the current transaction
     */
    public record PaymentGatewayStatusUpdate(
            UpdateTransactionStatusOutcome outcome,
            UpdateTransactionTrigger trigger,

            Optional<String> pspId
    )
            implements
            StatusUpdateInfo {

        public PaymentGatewayStatusUpdate {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(trigger);
            Objects.requireNonNull(pspId);
            if (!Set.of(
                    UpdateTransactionTrigger.NPG,
                    UpdateTransactionTrigger.PGS_XPAY,
                    UpdateTransactionTrigger.PGS_VPOS,
                    UpdateTransactionTrigger.REDIRECT,
                    UpdateTransactionTrigger.UNKNOWN).contains(trigger)) {
                throw new IllegalArgumentException(
                        "Invalid trigger for PaymentGatewayStatusUpdate: %s".formatted(trigger)
                );
            }
        }

        @Override
        public UpdateTransactionStatusType type() {
            return UpdateTransactionStatusType.AUTHORIZATION_OUTCOME;
        }

    }

    /**
     * Common interface for all status update information
     */
    public interface StatusUpdateInfo {
        /**
         * @return the update transaction status type
         * @see UpdateTransactionStatusType
         */
        UpdateTransactionStatusType type();

        /**
         * @return the update transaction trigger
         * @see UpdateTransactionTrigger
         */
        UpdateTransactionTrigger trigger();

        /**
         * @return the update transaction outcome
         * @see UpdateTransactionStatusOutcome
         */
        UpdateTransactionStatusOutcome outcome();

        /**
         * The id of the psp chosen by the user
         *
         * @return the id of the PSP
         */
        Optional<String> pspId();
    }

}
