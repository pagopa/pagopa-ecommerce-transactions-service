package it.pagopa.transactions.commands;

import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.domain.RptId;

public final class TransactionClosureSendCommand extends TransactionsCommand<ClosureSendData> {
    public TransactionClosureSendCommand(RptId rptId, ClosureSendData data) {
        super(rptId, TransactionsCommandCode.SEND_CLOSURE, data);
    }
}
