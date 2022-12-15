package it.pagopa.transactions.repositories;

import it.pagopa.ecommerce.commons.documents.TransactionActivationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.TransactionEventCode;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface TransactionsActivationRequestedEventStoreRepository
        extends ReactiveCrudRepository<TransactionActivationRequestedEvent, String> {

    Mono<TransactionActivationRequestedEvent> findByEventCodeAndData_PaymentContextCode(
                                                                                        TransactionEventCode transactionEventCode,
                                                                                        String paymentContextCode
    );
}
