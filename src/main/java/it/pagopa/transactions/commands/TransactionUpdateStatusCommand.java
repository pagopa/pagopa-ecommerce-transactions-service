package it.pagopa.transactions.commands;

import it.pagopa.transactions.commands.data.UpdateTransactionStatusData;
import it.pagopa.transactions.domain.RptId;

public final class TransactionUpdateStatusCommand extends TransactionsCommand<UpdateTransactionStatusData> {
    public TransactionUpdateStatusCommand(RptId rptId, UpdateTransactionStatusData data) {
        super(rptId, TransactionsCommandCode.UPDATE_AUTHORIZATION_STATUS, data);
    }
}
