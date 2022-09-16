package it.pagopa.transactions.domain;

import it.pagopa.transactions.annotations.AggregateRoot;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.transactions.domain.pojos.BaseTransaction;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithRequestedAuthorization;

@AggregateRoot
public final class TransactionWithRequestedAuthorization extends BaseTransactionWithRequestedAuthorization implements EventUpdatable<TransactionWithCompletedAuthorization, TransactionAuthorizationStatusUpdatedEvent>, Transaction {
    TransactionWithRequestedAuthorization(BaseTransaction transaction, TransactionAuthorizationRequestedEvent event) {
        super(transaction, event.getData());
    }

    @Override
    public TransactionWithCompletedAuthorization applyEvent(TransactionAuthorizationStatusUpdatedEvent event) {
        return new TransactionWithCompletedAuthorization(this, event);
    }
}
