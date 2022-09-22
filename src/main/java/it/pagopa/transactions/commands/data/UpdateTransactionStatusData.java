package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.UpdateTransactionStatusRequestDto;
import it.pagopa.transactions.domain.TransactionInitialized;

public record UpdateTransactionStatusData(
        TransactionInitialized transaction,
        UpdateTransactionStatusRequestDto updateTransactionRequest
) {}
