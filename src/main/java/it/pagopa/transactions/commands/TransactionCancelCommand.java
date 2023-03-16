package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.v1.RptId;
import it.pagopa.ecommerce.commons.domain.v1.TransactionId;

public final class TransactionCancelCommand extends TransactionsCommand<TransactionId> {
    public TransactionCancelCommand(
            RptId rptId,
            TransactionId data
    ) {
        super(rptId, TransactionsCommandCode.USER_CANCEL_REQUEST, data);
    }
}
