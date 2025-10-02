package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.domain.v2.RptId;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;

import java.util.List;

public final class TransactionUserCancelCommand extends TransactionsCommand<TransactionId> {
    public TransactionUserCancelCommand(
            List<RptId> rptIds,
            TransactionId data,
            List<? extends BaseTransactionEvent<?>> events
    ) {
        super(rptIds, TransactionsCommandCode.USER_CANCEL_REQUEST, data, events);
    }
}
