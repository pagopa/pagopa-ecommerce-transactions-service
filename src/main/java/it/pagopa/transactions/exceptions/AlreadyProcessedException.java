package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.util.Optional;

@ResponseStatus(value = HttpStatus.CONFLICT)
public class AlreadyProcessedException extends Exception implements TransactionContext {
    @NotNull
    private final TransactionId transactionId;
    @Nullable
    private final String pspId;
    @Nullable
    private final String paymentTypeCode;
    @Nullable
    private final String clientId;
    @Nullable
    private final Boolean walletPayment;
    @Nullable
    private final UpdateTransactionStatusTracerUtils.GatewayOutcomeResult gatewayOutcomeResult;

    public AlreadyProcessedException(TransactionId transactionId) {
        this.transactionId = transactionId;
        this.pspId = null;
        this.paymentTypeCode = null;
        this.clientId = null;
        this.walletPayment = null;
        this.gatewayOutcomeResult = null;
    }

    public AlreadyProcessedException(
            TransactionId transactionId,
            String pspId,
            String paymentTypeCode,
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
