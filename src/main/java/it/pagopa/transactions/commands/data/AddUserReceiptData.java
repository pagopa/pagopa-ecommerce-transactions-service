package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.TransactionActivated;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;

public record AddUserReceiptData(
		TransactionActivated transaction,
		AddUserReceiptRequestDto addUserReceiptRequest
) {}
