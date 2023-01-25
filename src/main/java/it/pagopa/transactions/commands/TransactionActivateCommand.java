package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.documents.Transaction;
import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode(callSuper = true)
public final class TransactionActivateCommand extends TransactionsCommand<NewTransactionRequestDto> {

    private final Transaction.ClientId clientId;

    public TransactionActivateCommand(
            RptId rptId,
            NewTransactionRequestDto data,
            Transaction.ClientId clientId
    ) {
        super(rptId, TransactionsCommandCode.ACTIVATE, data);
        this.clientId = clientId;
    }

}
