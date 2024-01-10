package it.pagopa.transactions.utils;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeVposGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestOutcomeGatewayDto;
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
                statusUpdateInfo.trigger().toString()
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
        @Override
        public UpdateTransactionStatusType type() {
            return UpdateTransactionStatusType.SEND_PAYMENT_RESULT_OUTCOME;
        }

        @Override
        public UpdateTransactionTrigger trigger() {
            return UpdateTransactionTrigger.NODO;
        }

    }

    /**
     * Transaction status update record for payment transaction gateway update
     * trigger
     *
     * @param outcome           - the transaction update status outcome
     * @param outcomeGatewayDto - the request gateway dto
     */
    public record PaymentGatewayStatusUpdate(
            UpdateTransactionStatusOutcome outcome,
            UpdateAuthorizationRequestOutcomeGatewayDto outcomeGatewayDto
    )
            implements
            StatusUpdateInfo {
        @Override
        public UpdateTransactionStatusType type() {
            return UpdateTransactionStatusType.AUTHORIZATION_OUTCOME;
        }

        @Override
        public UpdateTransactionTrigger trigger() {
            return switch (outcomeGatewayDto) {
                case OutcomeNpgGatewayDto ignored -> UpdateTransactionTrigger.NPG;
                case OutcomeXpayGatewayDto ignored -> UpdateTransactionTrigger.PGS_XPAY;
                case OutcomeVposGatewayDto ignored -> UpdateTransactionTrigger.PGS_VPOS;
                case null, default -> UpdateTransactionTrigger.UNKNOWN;
            };
        }

    }

    /**
     * Common interface for all status update information
     */
    public interface StatusUpdateInfo {
        UpdateTransactionStatusType type();

        UpdateTransactionTrigger trigger();

        UpdateTransactionStatusOutcome outcome();
    }

}
