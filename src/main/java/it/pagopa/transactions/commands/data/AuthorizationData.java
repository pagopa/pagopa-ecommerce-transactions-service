package it.pagopa.transactions.commands.data;

import it.pagopa.transactions.domain.Transaction;

public record AuthorizationData(
        Transaction transaction,
        int fee,
        String paymentInstrumentId,
        String pspId
) {}
