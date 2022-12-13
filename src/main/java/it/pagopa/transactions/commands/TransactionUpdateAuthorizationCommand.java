package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;

public final class TransactionUpdateAuthorizationCommand extends TransactionsCommand<UpdateAuthorizationStatusData> {
    public TransactionUpdateAuthorizationCommand(RptId rptId, UpdateAuthorizationStatusData data) {
        super(rptId, TransactionsCommandCode.UPDATE_AUTHORIZATION_STATUS, data);
    }
}
