package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.domain.v2.RptId;
import it.pagopa.transactions.commands.data.ClosureSendData;

import java.util.List;

public final class TransactionClosureSendCommand extends TransactionsCommand<ClosureSendData> {
    public TransactionClosureSendCommand(
            List<RptId> rptIds,
            ClosureSendData data,
            List<? extends BaseTransactionEvent<?>> events
    ) {
        super(rptIds, TransactionsCommandCode.SEND_CLOSURE, data, events);
    }
}
