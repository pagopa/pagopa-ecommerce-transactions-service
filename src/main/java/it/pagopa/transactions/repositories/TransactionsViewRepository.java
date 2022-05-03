package it.pagopa.transactions.repositories;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import it.pagopa.transactions.documents.Transaction;

public interface TransactionsViewRepository extends ReactiveCrudRepository<Transaction, String> {
}
