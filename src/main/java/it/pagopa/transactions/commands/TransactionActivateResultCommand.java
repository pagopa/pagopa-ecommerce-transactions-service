package it.pagopa.transactions.commands;

import it.pagopa.transactions.commands.data.ActivationResultData;
import it.pagopa.transactions.domain.RptId;

public final class TransactionActivateResultCommand extends TransactionsCommand<ActivationResultData> {
    public TransactionActivateResultCommand(RptId rptId, ActivationResultData data) {
        super(rptId, TransactionsCommandCode.INITIALIZE_TRANSACTION, data);
    }
}
