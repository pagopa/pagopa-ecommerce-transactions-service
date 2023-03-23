package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.v1.RptId;
import it.pagopa.ecommerce.commons.domain.v1.TransactionId;

public final class TransactionUserCancelCommand extends TransactionsCommand<TransactionId> {
    public TransactionUserCancelCommand(
            RptId rptId,
            TransactionId data
    ) {
        super(rptId, TransactionsCommandCode.USER_CANCEL_REQUEST, data);
    }
}
