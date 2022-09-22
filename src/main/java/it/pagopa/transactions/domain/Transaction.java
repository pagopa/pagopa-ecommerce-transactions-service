package it.pagopa.transactions.domain;

import it.pagopa.transactions.annotations.AggregateRoot;

@AggregateRoot
public sealed interface Transaction permits
        EmptyTransaction,
        TransactionInitialized,
        TransactionWithRequestedAuthorization,
        TransactionWithCompletedAuthorization,
        TransactionClosed
{
    <E> Transaction applyEvent(E event);
}
