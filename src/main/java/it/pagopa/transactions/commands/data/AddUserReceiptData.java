package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;

public record AddUserReceiptData(
        TransactionId transactionId,
        AddUserReceiptRequestDto addUserReceiptRequest
) {
}
