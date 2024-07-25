package it.pagopa.transactions.controllers.v1;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;

import java.util.Optional;

public record SendPaymentResultOutcomeInfo(
        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome outcome,
        Optional<TransactionId> transactionId,
        Optional<String> pspId,
        Optional<String> paymentTypeCode,
        Optional<Transaction.ClientId> clientId,
        Optional<Boolean> walletPayment,

        Optional<UpdateTransactionStatusTracerUtils.GatewayOutcomeResult> gatewayOutcomeResult
) {
}
