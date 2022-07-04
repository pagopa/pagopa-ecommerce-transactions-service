package it.pagopa.transactions.commands;

import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.domain.RptId;

public final class TransactionUpdateAuthorizationCommand extends TransactionsCommand<UpdateAuthorizationStatusData> {
    public TransactionUpdateAuthorizationCommand(RptId rptId, UpdateAuthorizationStatusData data) {
        super(rptId, TransactionsCommandCode.UPDATE_AUTHORIZATION_STATUS, data);
    }
}
