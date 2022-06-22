package it.pagopa.transactions.commands;

import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.domain.RptId;

public final class TransactionAuthorizeCommand extends TransactionsCommand<AuthorizationRequestData> {
    public TransactionAuthorizeCommand(RptId rptId, AuthorizationRequestData data) {
        super(rptId, TransactionsCommandCode.AUTHORIZE_TRANSACTION, data);
    }
}
