package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.v1.RptId;
import it.pagopa.transactions.commands.data.UserCancellationRequestData;

public final class TransactionCancelCommand extends TransactionsCommand<UserCancellationRequestData> {
    public TransactionCancelCommand(
            RptId rptId,
            UserCancellationRequestData data
    ) {
        super(rptId, TransactionsCommandCode.USER_CANCEL_REQUEST, data);
    }
}
