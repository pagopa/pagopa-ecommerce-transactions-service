package it.pagopa.transactions.commands;

import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.domain.RptId;

public final class TransactionActivateCommand extends TransactionsCommand<NewTransactionRequestDto> {
    public TransactionActivateCommand(RptId rptId, NewTransactionRequestDto data) {
        super(rptId, TransactionsCommandCode.ACTIVATE, data);
    }
}
