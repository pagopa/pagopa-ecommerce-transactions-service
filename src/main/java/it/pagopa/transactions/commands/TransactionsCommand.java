package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.domain.v2.RptId;
import lombok.Data;

import java.util.List;

@Data
public abstract sealed class TransactionsCommand<T> permits TransactionClosureSendCommand,TransactionActivateCommand,TransactionRequestAuthorizationCommand,TransactionUpdateAuthorizationCommand,TransactionAddUserReceiptCommand,TransactionUserCancelCommand,TransactionClosureRequestCommand {
    protected final List<RptId> rptIds;
    protected final TransactionsCommandCode code;
    protected final T data;
    private final List<? extends BaseTransactionEvent<?>> events;

}
