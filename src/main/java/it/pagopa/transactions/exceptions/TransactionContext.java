package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;

import java.util.Optional;

public interface TransactionContext {

    TransactionId getTransactionId();

    Optional<String> pspId();

    Optional<String> paymentTypeCode();

    String clientId();

    Boolean walletPayment();

    UpdateTransactionStatusTracerUtils.GatewayOutcomeResult gatewayOutcomeResult();

}
