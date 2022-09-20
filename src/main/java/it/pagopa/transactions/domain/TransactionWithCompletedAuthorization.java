package it.pagopa.transactions.domain;

import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.transactions.documents.TransactionClosureSentEvent;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithCompletedAuthorization;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithRequestedAuthorization;

public final class TransactionWithCompletedAuthorization extends BaseTransactionWithCompletedAuthorization implements EventUpdatable<TransactionClosed, TransactionClosureSentEvent>, Transaction {
    public TransactionWithCompletedAuthorization(BaseTransactionWithRequestedAuthorization baseTransaction, TransactionAuthorizationStatusUpdatedEvent event) {
        super(baseTransaction, event.getData());
    }

    @Override
    public TransactionClosed apply(TransactionClosureSentEvent event) {
        return new TransactionClosed(this, event);
    }

    @Override
    public <E> Transaction applyEvent(E event) {
        if (event instanceof TransactionClosureSentEvent) {
            return this.apply((TransactionClosureSentEvent) event);
        } else {
            return this;
        }
    }
}
