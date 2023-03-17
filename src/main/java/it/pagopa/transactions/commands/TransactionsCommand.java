package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.v1.*;
import lombok.Data;

@Data
public abstract sealed class TransactionsCommand<T> permits TransactionClosureSendCommand,TransactionActivateCommand,TransactionRequestAuthorizationCommand,TransactionUpdateAuthorizationCommand,TransactionAddUserReceiptCommand,TransactionUserCancelCommand {
    protected final RptId rptId;
    protected final TransactionsCommandCode code;
    protected final T data;
}
