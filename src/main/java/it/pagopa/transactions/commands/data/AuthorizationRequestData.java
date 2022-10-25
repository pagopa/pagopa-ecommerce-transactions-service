package it.pagopa.transactions.commands.data;

import it.pagopa.transactions.domain.TransactionActivated;

public record AuthorizationRequestData(
		TransactionActivated transaction,
        int fee,
        String paymentInstrumentId,
        String pspId,
        String paymentTypeCode,
        String brokerName,
        String pspChannelCode,
        String paymentMethodName,
        String pspBusinessName
) {}
