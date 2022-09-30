package it.pagopa.transactions.domain;

import it.pagopa.transactions.documents.TransactionClosureSentEvent;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.domain.pojos.BaseTransactionClosed;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithCompletedAuthorization;

public final class TransactionClosed extends BaseTransactionClosed implements Transaction {
    public TransactionClosed(BaseTransactionWithCompletedAuthorization baseTransaction, TransactionClosureSentEvent event) {
        super(baseTransaction, event.getData());
    }

    @Override
    public Transaction applyEvent(TransactionEvent<?> event) {
        return this;
    }
}
