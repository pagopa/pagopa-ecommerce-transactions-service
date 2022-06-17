package it.pagopa.transactions.commands;

import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.domain.RptId;

public final class TransactionInitializeCommand extends TransactionsCommand<NewTransactionRequestDto> {
    public TransactionInitializeCommand(RptId rptId, NewTransactionRequestDto data) {
        super(rptId, TransactionsCommandCode.INITIALIZE_TRANSACTION, data);
    }
}
