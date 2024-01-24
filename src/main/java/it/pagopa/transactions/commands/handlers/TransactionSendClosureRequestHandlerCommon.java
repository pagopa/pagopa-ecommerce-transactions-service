package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import reactor.core.publisher.Mono;

public abstract class TransactionSendClosureRequestHandlerCommon implements
        CommandHandler<TransactionSendClosureRequestHandlerCommon, Mono<BaseTransactionEvent<?>>> {

    protected final TracingUtils tracingUtils;
    protected final int transientQueuesTTLSeconds;
    protected final QueueAsyncClient transactionClosureQueueAsyncClient;

    protected TransactionSendClosureRequestHandlerCommon(
            TracingUtils tracingUtils,
            int transientQueuesTTLSeconds,
            QueueAsyncClient transactionClosureQueueAsyncClient
    ) {
        this.tracingUtils = tracingUtils;
        this.transientQueuesTTLSeconds = transientQueuesTTLSeconds;
        this.transactionClosureQueueAsyncClient = transactionClosureQueueAsyncClient;
    }
}
