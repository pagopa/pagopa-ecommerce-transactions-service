package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.domain.TransactionId;

import java.util.List;

public final class TransactionClosureRequestCommand extends TransactionsCommand<TransactionId> {

    public TransactionClosureRequestCommand(
            List<RptId> rptIds,
            TransactionId data
    ) {
        super(rptIds, TransactionsCommandCode.SEND_CLOSURE_REQUEST, data);
    }
}
