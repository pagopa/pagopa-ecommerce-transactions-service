package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction;
import it.pagopa.ecommerce.commons.domain.v1.Transaction;
import it.pagopa.ecommerce.commons.domain.v1.TransactionId;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class TransactionsUtils {

    private final TransactionsEventStoreRepository<Object> eventStoreRepository;

    @Autowired
    public TransactionsUtils(TransactionsEventStoreRepository<Object> eventStoreRepository) {
        this.eventStoreRepository = eventStoreRepository;
    }

    public Mono<BaseTransaction> reduceEvents(TransactionId transactionId) {
        return eventStoreRepository.findByTransactionId(transactionId.value().toString())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId.value().toString())))
                .reduce(new EmptyTransaction(), Transaction::applyEvent)
                .cast(BaseTransaction.class);
    }

}
