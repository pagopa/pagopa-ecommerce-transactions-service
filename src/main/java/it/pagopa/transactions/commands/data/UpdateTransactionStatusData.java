package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.UpdateTransactionStatusRequestDto;
import it.pagopa.transactions.domain.Transaction;

public record UpdateTransactionStatusData(
        Transaction transaction,
        UpdateTransactionStatusRequestDto updateTransactionRequest
) {}
