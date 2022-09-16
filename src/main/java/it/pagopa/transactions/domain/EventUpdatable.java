package it.pagopa.transactions.domain;

import it.pagopa.transactions.documents.TransactionEvent;

public interface EventUpdatable<T, E> {
    T applyEvent(E event);
}
