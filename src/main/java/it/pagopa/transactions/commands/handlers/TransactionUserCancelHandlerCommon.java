package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.transactions.commands.TransactionUserCancelCommand;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
public abstract class TransactionUserCancelHandlerCommon
        implements
        CommandHandler<TransactionUserCancelCommand, Mono<BaseTransactionEvent<?>>> {

    protected final TracingUtils tracingUtils;

    protected final TransactionsUtils transactionsUtils;
    protected final int transientQueuesTTLSeconds;
    protected final QueueAsyncClient transactionClosureQueueAsyncClient;

    protected TransactionUserCancelHandlerCommon(
            TracingUtils tracingUtils,
            TransactionsUtils transactionsUtils,
            int transientQueuesTTLSeconds,
            QueueAsyncClient transactionClosureQueueAsyncClient
    ) {
        this.tracingUtils = tracingUtils;
        this.transactionsUtils = transactionsUtils;
        this.transientQueuesTTLSeconds = transientQueuesTTLSeconds;
        this.transactionClosureQueueAsyncClient = transactionClosureQueueAsyncClient;
    }
}
