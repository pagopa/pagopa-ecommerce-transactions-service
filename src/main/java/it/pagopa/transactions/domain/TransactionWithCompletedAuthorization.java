package it.pagopa.transactions.domain;

import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.transactions.documents.TransactionClosureSentEvent;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithCompletedAuthorization;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithRequestedAuthorization;

public class TransactionWithCompletedAuthorization extends BaseTransactionWithCompletedAuthorization implements EventUpdatable<TransactionClosed, TransactionClosureSentEvent> {
    public TransactionWithCompletedAuthorization(BaseTransactionWithRequestedAuthorization baseTransaction, TransactionAuthorizationStatusUpdatedEvent event) {
        super(baseTransaction, event.getData());
    }

    @Override
    public TransactionClosed applyEvent(TransactionClosureSentEvent event) {
        return new TransactionClosed(this, event);
    }
}
