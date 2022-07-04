package it.pagopa.transactions.commands;

import it.pagopa.transactions.commands.data.ClosureRequestData;
import it.pagopa.transactions.domain.RptId;

public final class TransactionClosureRequestCommand extends TransactionsCommand<ClosureRequestData> {
    public TransactionClosureRequestCommand(RptId rptId, ClosureRequestData data) {
        super(rptId, TransactionsCommandCode.UPDATE_AUTHORIZATION_STATUS, data);
    }
}
