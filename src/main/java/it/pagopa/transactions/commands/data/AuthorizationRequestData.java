package it.pagopa.transactions.commands.data;

import it.pagopa.transactions.domain.TransactionInitialized;

public record AuthorizationRequestData(
        TransactionInitialized transaction,
        int fee,
        String paymentInstrumentId,
        String pspId,
        String paymentTypeCode,
        String brokerName,
        String pspChannelCode
) {}
