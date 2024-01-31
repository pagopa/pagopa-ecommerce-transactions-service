package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import lombok.Data;

@Data
public abstract sealed class TransactionsCommand<T> permits TransactionClosureSendCommand,TransactionActivateCommand,TransactionRequestAuthorizationCommand,TransactionUpdateAuthorizationCommand,TransactionAddUserReceiptCommand,TransactionUserCancelCommand,TransactionClosureRequestCommand {
    protected final RptId rptId;
    protected final TransactionsCommandCode code;
    protected final T data;
}
