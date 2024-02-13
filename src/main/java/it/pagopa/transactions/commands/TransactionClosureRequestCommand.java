package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.domain.TransactionId;

public final class TransactionClosureRequestCommand extends TransactionsCommand<TransactionId> {

    public TransactionClosureRequestCommand(
            RptId rptId,
            TransactionId data
    ) {
        super(rptId, TransactionsCommandCode.SEND_CLOSURE_REQUEST, data);
    }
}
