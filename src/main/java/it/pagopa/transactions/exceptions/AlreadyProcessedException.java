package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Optional;

@ResponseStatus(value = HttpStatus.CONFLICT)
public class AlreadyProcessedException extends Exception implements TransactionContext {
    private final TransactionId transactionId;
    private final Optional<String> pspId;
    private final Optional<String> paymentTypeCode;
    private final String clientId;
    private final Boolean walletPayment;
    private final UpdateTransactionStatusTracerUtils.GatewayOutcomeResult gatewayOutcomeResult;

    public AlreadyProcessedException(TransactionId transactionId) {
        this.transactionId = transactionId;
        this.pspId = Optional.empty();
        this.paymentTypeCode = Optional.empty();
        this.clientId = null;
        this.walletPayment = null;
        this.gatewayOutcomeResult = null;
    }

    public AlreadyProcessedException(
            TransactionId transactionId,
            Optional<String> pspId,
            Optional<String> paymentTypeCode,
            String clientId,
            Boolean walletPayment,
            UpdateTransactionStatusTracerUtils.GatewayOutcomeResult gatewayOutcomeResult
    ) {
        this.transactionId = transactionId;
        this.pspId = pspId;
        this.paymentTypeCode = paymentTypeCode;
        this.clientId = clientId;
        this.walletPayment = walletPayment;
        this.gatewayOutcomeResult = gatewayOutcomeResult;
    }

    @Override
    public TransactionId getTransactionId() {
        return transactionId;
    }

    @Override
    public Optional<String> pspId() {
        return pspId;
    }

    @Override
    public Optional<String> paymentTypeCode() {
        return paymentTypeCode;
    }

    @Override
    public String clientId() {
        return clientId;
    }

    @Override
    public Boolean walletPayment() {
        return walletPayment;
    }

    @Override
    public UpdateTransactionStatusTracerUtils.GatewayOutcomeResult gatewayOutcomeResult() {
        return gatewayOutcomeResult;
    }
}
