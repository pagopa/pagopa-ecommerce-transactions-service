package it.pagopa.transactions.repositories;

import it.pagopa.transactions.utils.TransactionEventCode;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import it.pagopa.transactions.documents.TransactionEvent;
import reactor.core.publisher.Mono;

public interface TransactionsEventStoreRepository<T> extends ReactiveCrudRepository<TransactionEvent<T>, String> {
    Mono<TransactionEvent<T>> findByPaymentTokenAndEventCode(String paymentToken, TransactionEventCode transactionEventCode);
}
