package it.pagopa.transactions.commands;
import it.pagopa.ecommerce.commons.domain.v1.RptId;
import it.pagopa.transactions.commands.data.UserCancelData;

public final class TransactionCancelCommand extends TransactionsCommand<UserCancelData> {
    public TransactionCancelCommand(
            RptId rptId,
            UserCancelData data
    ) {
        super(rptId, TransactionsCommandCode.USER_CANCEL_REQUEST, data);
    }
}
