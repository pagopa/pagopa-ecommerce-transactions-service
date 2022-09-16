package it.pagopa.transactions.domain;

import it.pagopa.transactions.documents.TransactionClosureSentEvent;
import it.pagopa.transactions.domain.pojos.BaseTransactionClosed;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithCompletedAuthorization;

public class TransactionClosed extends BaseTransactionClosed {
    public TransactionClosed(BaseTransactionWithCompletedAuthorization baseTransaction, TransactionClosureSentEvent event) {
        super(baseTransaction, event.getData());
    }
}
