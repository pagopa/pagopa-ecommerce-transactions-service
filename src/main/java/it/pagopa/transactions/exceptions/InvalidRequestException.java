package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;

import java.util.Optional;

public class InvalidRequestException extends RuntimeException implements TransactionContext {

    private final TransactionId transactionId;
    private final String pspId;
    private final String paymentTypeCode;
    private final String clientId;
    private final Boolean walletPayment;
    private final UpdateTransactionStatusTracerUtils.GatewayOutcomeResult gatewayOutcomeResult;

    public InvalidRequestException(String message) {
        super(message);
        this.transactionId = null;
        this.pspId = null;
        this.paymentTypeCode = null;
        this.clientId = null;
        this.walletPayment = null;
        this.gatewayOutcomeResult = null;
    }

    public InvalidRequestException(
            String message,
            Throwable t
    ) {
        super(message, t);
        this.transactionId = null;
        this.pspId = null;
        this.paymentTypeCode = null;
        this.clientId = null;
        this.walletPayment = null;
        this.gatewayOutcomeResult = null;
    }

    public InvalidRequestException(
            String message,
            TransactionId transactionId,
            String pspId,
            String paymentTypeCode,
            String clientId,
            Boolean walletPayment,
            UpdateTransactionStatusTracerUtils.GatewayOutcomeResult gatewayOutcomeResult
    ) {
        super(message);
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
        return Optional.ofNullable(pspId);
    }

    @Override
    public Optional<String> paymentTypeCode() {
        return Optional.ofNullable(paymentTypeCode);
    }

    @Override
    public Optional<String> clientId() {
        return Optional.ofNullable(clientId);
    }

    @Override
    public Optional<Boolean> walletPayment() {
        return Optional.ofNullable(walletPayment);
    }

    @Override
    public Optional<UpdateTransactionStatusTracerUtils.GatewayOutcomeResult> gatewayOutcomeResult() {
        return Optional.ofNullable(gatewayOutcomeResult);
    }
}
