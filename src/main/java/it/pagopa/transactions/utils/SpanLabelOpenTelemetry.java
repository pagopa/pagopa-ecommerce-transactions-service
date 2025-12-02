package it.pagopa.transactions.utils;

import io.opentelemetry.api.common.AttributeKey;

public class SpanLabelOpenTelemetry {

    /**
     * Nodo activation
     */
    public static final String NODO_ACTIVATION_ERROR_SPAN_NAME = "ActivatePaymentNoticeV2 nodo error: [%s]";

    public static final String NODO_ACTIVATION_OK_SPAN_NAME = "ActivatePaymentNoticeV2 nodo ok";
    public static final AttributeKey<String> NODO_ACTIVATION_ERROR_FAULT_CODE_ATTRIBUTE_KEY = AttributeKey
            .stringKey("faultCode");

    public static final String REPEATED_ACTIVATION_SPAN_NAME = "Transaction re-activated";

    public static final AttributeKey<String> REPEATED_ACTIVATION_PAYMENT_TOKEN_ATTRIBUTE_KEY = AttributeKey
            .stringKey("paymentToken");

    public static final AttributeKey<Long> REPEATED_ACTIVATION_PAYMENT_TOKEN_LEFT_TIME_ATTRIBUTE_KEY = AttributeKey
            .longKey("paymentTokenLeftTimeSec");

    public static final String CIRCUIT_BREAKER_OPEN_SPAN_NAME = "Circuit Breaker [%s] open";

    public static final String GET_TRANSACTIONS_OUTCOMES_SPAN_NAME = "Get transactions outcome";

    public static final AttributeKey<String> GET_TRANSACTIONS_OUTCOMES_SPAN_OUTCOME_ATTRIBUTE_KEY = AttributeKey
            .stringKey("getTransactionsOutcomes.outcome");

    public static final AttributeKey<String> GET_TRANSACTIONS_OUTCOMES_SPAN_IS_FINAL_STATUS_FLAG_ATTRIBUTE_KEY = AttributeKey
            .stringKey("getTransactionsOutcomes.isFinalStatus");

    public static final AttributeKey<String> GET_TRANSACTIONS_OUTCOMES_SPAN_TRANSACTION_ID_ATTRIBUTE_KEY = AttributeKey
            .stringKey("getTransactionsOutcomes.transactionId");

    public static final AttributeKey<String> GET_TRANSACTIONS_OUTCOMES_SPAN_TRANSACTION_STATUS_ATTRIBUTE_KEY = AttributeKey
            .stringKey("getTransactionsOutcomes.transactionStatus");

}
