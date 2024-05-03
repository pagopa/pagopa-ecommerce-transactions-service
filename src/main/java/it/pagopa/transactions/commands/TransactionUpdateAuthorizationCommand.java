package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;

import java.util.List;

public final class TransactionUpdateAuthorizationCommand extends TransactionsCommand<UpdateAuthorizationStatusData> {
    public TransactionUpdateAuthorizationCommand(
            List<RptId> rptIds,
            UpdateAuthorizationStatusData data
    ) {
        super(rptIds, TransactionsCommandCode.UPDATE_AUTHORIZATION_STATUS, data);
    }
}
