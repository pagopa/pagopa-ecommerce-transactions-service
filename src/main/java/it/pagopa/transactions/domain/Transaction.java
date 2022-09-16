package it.pagopa.transactions.domain;

import it.pagopa.transactions.annotations.AggregateRoot;

@AggregateRoot
public sealed interface Transaction permits
        TransactionInitialized,
        TransactionWithRequestedAuthorization,
        TransactionWithCompletedAuthorization,
        TransactionClosed
{}
