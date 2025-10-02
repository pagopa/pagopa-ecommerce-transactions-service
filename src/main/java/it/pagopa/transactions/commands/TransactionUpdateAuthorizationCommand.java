package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.domain.v2.RptId;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;

import java.util.List;

public final class TransactionUpdateAuthorizationCommand extends TransactionsCommand<UpdateAuthorizationStatusData> {
    public TransactionUpdateAuthorizationCommand(
            List<RptId> rptIds,
            UpdateAuthorizationStatusData data,
            List<? extends BaseTransactionEvent<?>> events
    ) {
        super(rptIds, TransactionsCommandCode.UPDATE_AUTHORIZATION_STATUS, data, events);
    }
}
