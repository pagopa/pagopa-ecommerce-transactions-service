package it.pagopa.transactions.repositories;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import it.pagopa.transactions.documents.TransactionEvent;

public interface TransactionsEventStoreRepository<T> extends ReactiveCrudRepository<TransactionEvent<T>, String> {
}
