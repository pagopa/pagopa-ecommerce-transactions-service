package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;

import java.util.List;

public final class TransactionRequestAuthorizationCommand extends TransactionsCommand<AuthorizationRequestData> {
    public TransactionRequestAuthorizationCommand(
            List<RptId> rptIds,
            AuthorizationRequestData data
    ) {
        super(rptIds, TransactionsCommandCode.REQUEST_AUTHORIZATION, data);
    }
}
