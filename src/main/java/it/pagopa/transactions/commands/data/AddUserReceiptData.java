package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;
import it.pagopa.transactions.domain.TransactionActivated;

public record AddUserReceiptData(
		TransactionActivated transaction,
		AddUserReceiptRequestDto addUserReceiptRequest
) {}
