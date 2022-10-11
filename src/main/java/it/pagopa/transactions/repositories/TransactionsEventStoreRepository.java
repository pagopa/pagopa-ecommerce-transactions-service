package it.pagopa.transactions.repositories;

import it.pagopa.transactions.utils.TransactionEventCode;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import it.pagopa.transactions.documents.TransactionEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TransactionsEventStoreRepository<T> extends ReactiveCrudRepository<TransactionEvent<T>, String> {
    Mono<TransactionEvent<T>> findByTransactionIdAndEventCode(String idTransaction, TransactionEventCode transactionEventCode);

    Flux<TransactionEvent<T>> findByTransactionId(String transactionId);
}
