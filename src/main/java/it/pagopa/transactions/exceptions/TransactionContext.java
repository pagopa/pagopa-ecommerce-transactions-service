package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;

import java.util.Optional;

public interface TransactionContext {

    TransactionId getTransactionId();

    Optional<String> pspId();

    Optional<String> paymentTypeCode();

    Optional<String> clientId();

    Optional<Boolean> walletPayment();

    Optional<UpdateTransactionStatusTracerUtils.GatewayOutcomeResult> gatewayOutcomeResult();

}
