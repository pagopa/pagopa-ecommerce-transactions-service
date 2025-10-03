package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.domain.v2.RptId;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;

import java.util.List;

public final class TransactionClosureRequestCommand extends TransactionsCommand<TransactionId> {

    public TransactionClosureRequestCommand(
            List<RptId> rptIds,
            TransactionId data,
            List<? extends BaseTransactionEvent<?>> events
    ) {
        super(rptIds, TransactionsCommandCode.SEND_CLOSURE_REQUEST, data, events);
    }
}
