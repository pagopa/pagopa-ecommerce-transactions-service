package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;

public record AddUserReceiptData(
        TransactionId transactionId,
        AddUserReceiptRequestDto addUserReceiptRequest
) {
}
