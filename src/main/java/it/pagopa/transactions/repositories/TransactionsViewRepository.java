package it.pagopa.transactions.repositories;

import it.pagopa.ecommerce.commons.documents.BaseTransactionView;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface TransactionsViewRepository extends ReactiveCrudRepository<BaseTransactionView, String> {
    Mono<BaseTransactionView> findByTransactionId(String transactionId);

    @Query(
            value = "{ 'transactionId' : ?0 , 'userId' : ?1}"
    )
    Mono<BaseTransactionView> findByTransactionIdAndUserId(
                                                           String transactionId,
                                                           String userId
    );

}
