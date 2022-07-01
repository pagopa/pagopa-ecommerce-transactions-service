package it.pagopa.transactions.commands.data;

import it.pagopa.transactions.domain.Transaction;

import java.util.UUID;

public record AuthorizationRequestData(
        Transaction transaction,
        int fee,
        String paymentInstrumentId,
        String pspId,
        String paymentTypeCode,
        String brokerName,
        String pspChannelCode,
        UUID transactionId
) {}
