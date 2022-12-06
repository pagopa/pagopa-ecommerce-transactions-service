package it.pagopa.transactions.commands.data;

import it.pagopa.transactions.domain.TransactionActivated;

import java.time.LocalDate;

public record AuthorizationRequestData(
		TransactionActivated transaction,
        int fee,
        String paymentInstrumentId,
        String pspId,
        String paymentTypeCode,
        String brokerName,
        String pspChannelCode,
        String paymentMethodName,
        String pspBusinessName,
        String gatewayId,
        String cvv,
        String pan,
        LocalDate expiryDate
) {}
