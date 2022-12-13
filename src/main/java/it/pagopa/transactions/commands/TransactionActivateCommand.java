package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;

public final class TransactionActivateCommand extends TransactionsCommand<NewTransactionRequestDto> {
    public TransactionActivateCommand(RptId rptId, NewTransactionRequestDto data) {
        super(rptId, TransactionsCommandCode.ACTIVATE, data);
    }
}
