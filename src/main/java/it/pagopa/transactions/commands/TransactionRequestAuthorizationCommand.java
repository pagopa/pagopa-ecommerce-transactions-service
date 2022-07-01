package it.pagopa.transactions.commands;

import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.domain.RptId;

public final class TransactionRequestAuthorizationCommand extends TransactionsCommand<AuthorizationRequestData> {
    public TransactionRequestAuthorizationCommand(RptId rptId, AuthorizationRequestData data) {
        super(rptId, TransactionsCommandCode.REQUEST_AUTHORIZATION, data);
    }
}
