package it.pagopa.transactions.commands.data;

import it.pagopa.transactions.domain.Transaction;

public record AuthorizationRequestData(
        Transaction transaction,
        int fee,
        String paymentInstrumentId,
        String pspId
) {}
