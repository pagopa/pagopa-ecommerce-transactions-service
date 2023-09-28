package it.pagopa.transactions.commands;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode(callSuper = true)
public final class TransactionActivateCommand extends TransactionsCommand<NewTransactionRequestDto> {

    private final String clientId;

    private final TransactionId transactionId;

    public TransactionActivateCommand(
            RptId rptId,
            NewTransactionRequestDto data,
            String clientId,
            TransactionId transactionId
    ) {
        super(rptId, TransactionsCommandCode.ACTIVATE, data);
        this.clientId = clientId;
        this.transactionId = transactionId;
    }

}
