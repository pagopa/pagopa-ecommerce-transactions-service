package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.v2.RptId;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;

import java.util.List;

public final class TransactionRequestAuthorizationCommand extends TransactionsCommand<AuthorizationRequestData> {
    public final String lang;

    public TransactionRequestAuthorizationCommand(
            List<RptId> rptIds,
            String lang,
            AuthorizationRequestData data
    ) {
        super(rptIds, TransactionsCommandCode.REQUEST_AUTHORIZATION, data);
        this.lang = lang;
    }
}
