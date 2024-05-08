package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.transactions.commands.data.AddUserReceiptData;

import java.util.List;

public final class TransactionAddUserReceiptCommand extends TransactionsCommand<AddUserReceiptData> {
    public TransactionAddUserReceiptCommand(
            List<RptId> rptIds,
            AddUserReceiptData data
    ) {
        super(rptIds, TransactionsCommandCode.UPDATE_TRANSACTION_STATUS, data);
    }
}
