package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.transactions.client.JwtTokenIssuerClient;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

@Slf4j
public abstract class TransactionActivateHandlerCommon
        implements
        CommandHandler<TransactionActivateCommand, Mono<Tuple2<Mono<BaseTransactionEvent<?>>, String>>> {

    public static final int TRANSFER_LIST_MAX_SIZE = 5;
    protected final Integer paymentTokenTimeout;
    protected final JwtTokenIssuerClient jwtTokenIssuerClient;
    protected final ConfidentialMailUtils confidentialMailUtils;

    protected final int transientQueuesTTLSeconds;
    protected final int nodoParallelRequests;

    protected final TracingUtils tracingUtils;
    protected final OpenTelemetryUtils openTelemetryUtils;

    protected final int jwtEcommerceValidityTimeInSeconds;

    protected TransactionActivateHandlerCommon(

            Integer paymentTokenTimeout,
            JwtTokenIssuerClient jwtTokenIssuerClient,
            ConfidentialMailUtils confidentialMailUtils,
            int transientQueuesTTLSeconds,
            int nodoParallelRequests,
            TracingUtils tracingUtils,
            OpenTelemetryUtils openTelemetryUtils,
            int jwtEcommerceValidityTimeInSeconds
    ) {

        this.paymentTokenTimeout = paymentTokenTimeout;
        this.jwtTokenIssuerClient = jwtTokenIssuerClient;
        this.confidentialMailUtils = confidentialMailUtils;
        this.transientQueuesTTLSeconds = transientQueuesTTLSeconds;
        this.nodoParallelRequests = nodoParallelRequests;
        this.tracingUtils = tracingUtils;
        this.openTelemetryUtils = openTelemetryUtils;
        this.jwtEcommerceValidityTimeInSeconds = jwtEcommerceValidityTimeInSeconds;
    }
}
