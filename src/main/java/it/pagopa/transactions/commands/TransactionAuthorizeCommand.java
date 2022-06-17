package it.pagopa.transactions.commands;

import it.pagopa.transactions.commands.data.AuthorizationData;
import it.pagopa.transactions.domain.RptId;

public final class TransactionAuthorizeCommand extends TransactionsCommand<AuthorizationData> {
    public TransactionAuthorizeCommand(RptId rptId, AuthorizationData data) {
        super(rptId, TransactionsCommandCode.AUTHORIZE_TRANSACTION, data);
    }
}
