package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;

public final class TransactionRequestAuthorizationCommand extends TransactionsCommand<AuthorizationRequestData> {
    public TransactionRequestAuthorizationCommand(
            RptId rptId,
            AuthorizationRequestData data
    ) {
        super(rptId, TransactionsCommandCode.REQUEST_AUTHORIZATION, data);
    }
}
