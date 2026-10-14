package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import lombok.Builder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;

@Builder
@ResponseStatus(value = HttpStatus.CONFLICT)
public class ConflictStatusException extends Exception implements TransactionContext {
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
    @Nullable
    private final String transactionStatus;
    @Nullable
    private final String downstreamHttpStatus;
    @Nullable
    private final String downstreamResponseBody;

    public ConflictStatusException(TransactionId transactionId) {
        this.transactionId = transactionId;
        this.pspId = null;
        this.paymentTypeCode = null;
        this.clientId = null;
        this.walletPayment = null;
        this.gatewayOutcomeResult = null;
        this.transactionStatus = null;
        this.downstreamHttpStatus = null;
        this.downstreamResponseBody = null;
    }

    public ConflictStatusException(
            TransactionId transactionId,
            String downstreamHttpStatus,
            String downstreamResponseBody
    ) {
        this.transactionId = transactionId;
        this.downstreamHttpStatus = downstreamHttpStatus;
        this.downstreamResponseBody = downstreamResponseBody;
        this.pspId = null;
        this.paymentTypeCode = null;
        this.clientId = null;
        this.walletPayment = null;
        this.gatewayOutcomeResult = null;
        this.transactionStatus = null;
    }

    public ConflictStatusException(
            TransactionId transactionId,
            String pspId,
            String paymentTypeCode,
            String clientId,
            Boolean walletPayment,
            UpdateTransactionStatusTracerUtils.GatewayOutcomeResult gatewayOutcomeResult
    ) {
        this(
                transactionId,
                pspId,
                paymentTypeCode,
                clientId,
                walletPayment,
                gatewayOutcomeResult,
                null,
                null,
                null
        );
    }

    public ConflictStatusException(
            TransactionId transactionId,
            String pspId,
            String paymentTypeCode,
            String clientId,
            Boolean walletPayment,
            UpdateTransactionStatusTracerUtils.GatewayOutcomeResult gatewayOutcomeResult,
            String transactionStatus,
            String downstreamHttpStatus,
            String downstreamResponseBody
    ) {
        this.transactionId = transactionId;
        this.pspId = pspId;
        this.paymentTypeCode = paymentTypeCode;
        this.clientId = clientId;
        this.walletPayment = walletPayment;
        this.gatewayOutcomeResult = gatewayOutcomeResult;
        this.transactionStatus = transactionStatus;
        this.downstreamHttpStatus = downstreamHttpStatus;
        this.downstreamResponseBody = downstreamResponseBody;
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

    public Optional<String> transactionStatus() {
        return Optional.ofNullable(transactionStatus);
    }

    public Optional<String> downstreamHttpStatus() {
        return Optional.ofNullable(downstreamHttpStatus);
    }

    public Optional<String> downstreamResponseBody() {
        return Optional.ofNullable(downstreamResponseBody);
    }
}
