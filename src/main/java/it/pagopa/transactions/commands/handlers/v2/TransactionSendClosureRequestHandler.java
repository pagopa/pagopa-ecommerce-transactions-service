package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.transactions.commands.handlers.TransactionSendClosureRequestHandlerCommon;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Mono;

@Slf4j
public class TransactionSendClosureRequestHandler extends TransactionSendClosureRequestHandlerCommon {

    private final TransactionsEventStoreRepository<Void> transactionEventSendClosureRequestRepository;

    @Autowired
    protected TransactionSendClosureRequestHandler(
            TransactionsEventStoreRepository<Void> transactionEventSendClosureRequestRepository,
            @Qualifier("transactionClosureQueueAsyncClientV2") QueueAsyncClient transactionClosureQueueAsyncClient,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") int transientQueuesTTLSeconds,
            TracingUtils tracingUtils
    ) {
        super(tracingUtils, transientQueuesTTLSeconds, transactionClosureQueueAsyncClient);
        this.transactionEventSendClosureRequestRepository = transactionEventSendClosureRequestRepository;
    }

    @Override
    public Mono<BaseTransactionEvent<?>> handle(TransactionSendClosureRequestHandlerCommon command) {
        return null;
    }
}
