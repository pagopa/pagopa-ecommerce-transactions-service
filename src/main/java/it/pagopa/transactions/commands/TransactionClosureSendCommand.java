package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.transactions.commands.data.ClosureSendData;

public final class TransactionClosureSendCommand extends TransactionsCommand<ClosureSendData> {
    public TransactionClosureSendCommand(RptId rptId, ClosureSendData data) {
        super(rptId, TransactionsCommandCode.SEND_CLOSURE, data);
    }
}
