package it.pagopa.transactions.repositories;

import it.pagopa.ecommerce.commons.documents.TransactionEvent;
import it.pagopa.ecommerce.commons.domain.TransactionEventCode;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TransactionsEventStoreRepository<T> extends ReactiveCrudRepository<TransactionEvent<T>, String> {
    Mono<TransactionEvent<T>> findByTransactionIdAndEventCode(String idTransaction, TransactionEventCode transactionEventCode);

    Flux<TransactionEvent<T>> findByTransactionId(String transactionId);
}
