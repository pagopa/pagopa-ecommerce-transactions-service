package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
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
