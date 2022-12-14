package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import lombok.Data;

@Data
public abstract sealed class TransactionsCommand<T> permits TransactionActivateResultCommand, TransactionClosureSendCommand, TransactionActivateCommand, TransactionRequestAuthorizationCommand, TransactionUpdateAuthorizationCommand, TransactionAddUserReceiptCommand
{
    protected final RptId rptId;
    protected final TransactionsCommandCode code;
    protected final T data;
}
