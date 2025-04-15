package it.pagopa.transactions.mdcutilities;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import reactor.util.context.Context;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tracing utility class that contains helper methods to set transaction
 * information, such as transactionId and rptId list into reactor context
 */
public class TransactionTracingUtils {

    /**
     * Tracing keys enumerations that contains both context key and default value,
     * set in case such information are not taken from incoming request
     */
    public enum TracingEntry {
        TRANSACTION_ID("transactionId", "{transactionId-not-found}"),
        RPT_IDS("rptIds", "{rptId-not-found}"),
        CORRELATION_ID("correlationId", "{correlation-id-not-found}"),
        API_ID("apiId", "{api-id-not-found}"),
        CLIENT_ID("clientId", "{client-id-not-found}"),
        PSP_ID("pspId", "{psp-id-not-found}"),
        PAY_METHOD_ID("payMethodId", "{pay-method-id-not-found}");

        private final String key;

        private final String defaultValue;

        TracingEntry(
                String key,
                String defaultValue
        ) {
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public String getKey() {
            return key;
        }

        public String getDefaultValue() {
            return defaultValue;
        }
    }

    /**
     * Transaction information record
     */
    public record TransactionInfo(
            TransactionId transactionId,
            String clientId,
            String payMethodId,
            String pspId,
            Set<RptId> rptIds,
            String requestMethod,
            String requestUriPath
    ) {
    }

    /**
     * Set transaction information into context taking information from the input
     * TransactionInfo
     *
     * @param transactionInfo - the transaction information record from which
     *                        retrieve information to be set into context
     */
    public static Context setTransactionInfoIntoReactorContext(
                                                               TransactionInfo transactionInfo,
                                                               Context reactorContext
    ) {
        Context context = putInReactorContextIfSetToDefault(
                TracingEntry.TRANSACTION_ID,
                transactionInfo.transactionId.value(),
                reactorContext
        );
        if (!transactionInfo.rptIds.isEmpty()) {
            String stringifiedRptIdList = transactionInfo.rptIds.stream().map(RptId::value)
                    .collect(Collectors.joining(","));
            context = putInReactorContextIfSetToDefault(TracingEntry.RPT_IDS, stringifiedRptIdList, context);
        }

        if (transactionInfo.clientId != null && !transactionInfo.clientId.isEmpty()) {
            context = putInReactorContextIfSetToDefault(TracingEntry.CLIENT_ID, transactionInfo.clientId, reactorContext);
        }

        if (transactionInfo.pspId != null && !transactionInfo.pspId.isEmpty()) {
            context = putInReactorContextIfSetToDefault(TracingEntry.PSP_ID, transactionInfo.pspId, reactorContext);
        }

        if (transactionInfo.payMethodId != null && !transactionInfo.payMethodId.isEmpty()) {
            context = putInReactorContextIfSetToDefault(TracingEntry.PAY_METHOD_ID, transactionInfo.payMethodId, reactorContext);
        }

        context = putInReactorContextIfSetToDefault(
                TracingEntry.API_ID,
                String.join("-", "API-ID", transactionInfo.requestMethod, transactionInfo.requestUriPath),
                context
        );

        return context;
    }

    /**
     * Put value into context if the actual context value is not present or set to
     * its default value
     *
     * @param tracingEntry - the context entry to be value
     * @param valueToSet   - the value to set
     */
    private static Context putInReactorContextIfSetToDefault(
                                                             TracingEntry tracingEntry,
                                                             String valueToSet,
                                                             Context reactorContext
    ) {
        Context currentContext = reactorContext;
        if (tracingEntry.getDefaultValue()
                .equals(reactorContext.getOrDefault(tracingEntry.getKey(), tracingEntry.getDefaultValue()))) {
            currentContext = reactorContext.put(tracingEntry.getKey(), valueToSet);
        }
        return currentContext;
    }
}
