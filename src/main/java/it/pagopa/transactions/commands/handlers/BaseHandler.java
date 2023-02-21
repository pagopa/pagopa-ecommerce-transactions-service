package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent;
import it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction;
import it.pagopa.ecommerce.commons.domain.v1.Transaction;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

abstract class BaseHandler<T, S> implements CommandHandler<T, S> {

    private final TransactionsEventStoreRepository<Object> eventStoreRepository;

    protected BaseHandler(TransactionsEventStoreRepository<Object> eventStoreRepository) {
        this.eventStoreRepository = eventStoreRepository;
    }

    protected Mono<Transaction> replayTransactionEvents(UUID transactionId) {
        Flux<TransactionEvent<Object>> events = eventStoreRepository.findByTransactionId(transactionId.toString());
        return events.reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent);
    }
}
