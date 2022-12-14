package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.transactions.commands.data.AddUserReceiptData;

public final class TransactionAddUserReceiptCommand extends TransactionsCommand<AddUserReceiptData> {
    public TransactionAddUserReceiptCommand(RptId rptId, AddUserReceiptData data) {
        super(rptId, TransactionsCommandCode.UPDATE_TRANSACTION_STATUS, data);
    }
}
