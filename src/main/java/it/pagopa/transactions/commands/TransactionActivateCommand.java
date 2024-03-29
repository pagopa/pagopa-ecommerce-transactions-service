package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.transactions.commands.data.NewTransactionRequestData;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode(callSuper = true)
public final class TransactionActivateCommand extends TransactionsCommand<NewTransactionRequestData> {

    private final String clientId;

    private final TransactionId transactionId;

    public TransactionActivateCommand(
            RptId rptId,
            NewTransactionRequestData data,
            String clientId,
            TransactionId transactionId
    ) {
        super(rptId, TransactionsCommandCode.ACTIVATE, data);
        this.clientId = clientId;
        this.transactionId = transactionId;
    }

}
