package it.pagopa.transactions.utils;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
    public static final AttributeKey<String> UPDATE_TRANSACTION_STATUS_TYPE_ATTRIBUTE_KEY = AttributeKey
            .stringKey("updateTransactionStatus.type");
    /**
     * Spqn attribute used to trace status update outcome
     *
     * @see UpdateTransactionStatusOutcome
     */
    public static final AttributeKey<String> UPDATE_TRANSACTION_STATUS_OUTCOME_ATTRIBUTE_KEY = AttributeKey
            .stringKey("updateTransactionStatus.outcome");
    /**
     * Spqn attribute used to trace status update trigger
     *
     * @see UpdateTransactionTrigger
     */
    public static final AttributeKey<String> UPDATE_TRANSACTION_STATUS_TRIGGER_ATTRIBUTE_KEY = AttributeKey
            .stringKey("updateTransactionStatus.trigger");

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
        PGS_XPAY
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
         * Error processing transaction status update: an unexpected error has occurred
         * processing transaction update state
         */
        PROCESSING_ERROR
    }

    private static final String UPDATE_TRANSACTION_STATUS_SPAN_NAME = "Transaction status updated";

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
                statusUpdateInfo.type.toString(),
                UPDATE_TRANSACTION_STATUS_OUTCOME_ATTRIBUTE_KEY,
                statusUpdateInfo.outcome.toString(),
                UPDATE_TRANSACTION_STATUS_TRIGGER_ATTRIBUTE_KEY,
                statusUpdateInfo.trigger.toString()
        );
        openTelemetryUtils.addSpanWithAttributes(UPDATE_TRANSACTION_STATUS_SPAN_NAME, spanAttributes);
    }

    /**
     * Transaction status update information
     *
     * @param type    - status update type
     * @param trigger - status update trigger
     * @param outcome - status update outcome
     */
    public record StatusUpdateInfo(
            UpdateTransactionStatusType type,
            UpdateTransactionTrigger trigger,
            UpdateTransactionStatusOutcome outcome
    ) {
    }
}
