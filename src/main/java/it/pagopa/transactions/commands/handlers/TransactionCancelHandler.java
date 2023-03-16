package it.pagopa.transactions.commands.handlers;

import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestsInfoRepository;
import it.pagopa.transactions.commands.TransactionCancelCommand;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionCancelHandler extends
        BaseHandler<TransactionCancelCommand, Mono<TransactionUserCanceledEvent>> {

    private final PaymentRequestsInfoRepository paymentRequestsInfoRepository;
    private final TransactionsEventStoreRepository<Void> transactionEventUserCancelledStoreRepository;
    private final QueueAsyncClient transactionActivatedQueueAsyncClient;

    @Autowired
    public TransactionCancelHandler(
            TransactionsEventStoreRepository<Object> eventStoreRepository,
            PaymentRequestsInfoRepository paymentRequestsInfoRepository,
            TransactionsEventStoreRepository<Void> transactionEventUserCancelledStoreRepository,
            @Qualifier("transactionClosureQueueAsyncClient") QueueAsyncClient transactionActivatedQueueAsyncClient
    ) {
        super(eventStoreRepository);
        this.paymentRequestsInfoRepository = paymentRequestsInfoRepository;
        this.transactionEventUserCancelledStoreRepository = transactionEventUserCancelledStoreRepository;
        this.transactionActivatedQueueAsyncClient = transactionActivatedQueueAsyncClient;
    }

    @Override
    public Mono<TransactionUserCanceledEvent> handle(TransactionCancelCommand command) {
        // Aggiornare event Store
        // scrive sulla coda
        // restituisce l evento scritto
        return null;
    }
}
