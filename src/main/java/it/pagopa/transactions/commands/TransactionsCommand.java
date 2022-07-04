package it.pagopa.transactions.commands;

import it.pagopa.transactions.domain.RptId;
import lombok.Data;

@Data
public abstract sealed class TransactionsCommand<T>
        permits
        TransactionInitializeCommand,
        TransactionRequestAuthorizationCommand,
        TransactionUpdateAuthorizationCommand,
        TransactionClosureRequestCommand
{
    protected final RptId rptId;
    protected final TransactionsCommandCode code;
    protected final T data;
}
