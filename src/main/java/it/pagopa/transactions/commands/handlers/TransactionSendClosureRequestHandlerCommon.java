package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.transactions.commands.TransactionClosureRequestCommand;
import it.pagopa.transactions.utils.TransactionsUtils;
import reactor.core.publisher.Mono;

public abstract class TransactionSendClosureRequestHandlerCommon implements
        CommandHandler<TransactionClosureRequestCommand, Mono<BaseTransactionEvent<?>>> {

    protected final TracingUtils tracingUtils;
    protected final int transientQueuesTTLSeconds;
    protected final TransactionsUtils transactionsUtils;
    protected final QueueAsyncClient transactionClosureQueueAsyncClient;

    protected TransactionSendClosureRequestHandlerCommon(
            TracingUtils tracingUtils,
            int transientQueuesTTLSeconds,
            TransactionsUtils transactionsUtils,
            QueueAsyncClient transactionClosureQueueAsyncClient
    ) {
        this.tracingUtils = tracingUtils;
        this.transientQueuesTTLSeconds = transientQueuesTTLSeconds;
        this.transactionsUtils = transactionsUtils;
        this.transactionClosureQueueAsyncClient = transactionClosureQueueAsyncClient;
    }
}
