package it.pagopa.transactions.repositories;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TransactionsEventStoreRepository<T> extends ReactiveMongoRepository<BaseTransactionEvent<T>, String> {
    Mono<BaseTransactionEvent<T>> findByTransactionIdAndEventCode(
                                                                  String idTransaction,
                                                                  String transactionEventCode
    );

    Flux<BaseTransactionEvent<T>> findByTransactionIdOrderByCreationDateAsc(String transactionId);
}
