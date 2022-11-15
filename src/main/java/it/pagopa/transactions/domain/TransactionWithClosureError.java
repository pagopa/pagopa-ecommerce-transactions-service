package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.*;
import it.pagopa.transactions.domain.pojos.BaseTransactionClosed;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithClosureError;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithCompletedAuthorization;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
public final class TransactionWithClosureError extends BaseTransactionWithClosureError implements Transaction {
    public TransactionWithClosureError(BaseTransactionWithCompletedAuthorization baseTransaction, TransactionClosureErrorEvent event) {
        super(baseTransaction, event);
    }

    @Override
    public Transaction applyEvent(TransactionEvent<?> event) {
        return this;
    }

    @Override
    public TransactionWithClosureError withStatus(TransactionStatusDto status) {
        return new TransactionWithClosureError(
                new TransactionWithCompletedAuthorization(
                        new TransactionWithRequestedAuthorization(
                                new TransactionActivated(
                                        this.getTransactionId(),
                                        new PaymentToken(this.getTransactionActivatedData().getPaymentToken()),
                                        this.getRptId(),
                                        this.getDescription(),
                                        this.getAmount(),
                                        this.getEmail(),
                                        this.getTransactionActivatedData().getFaultCode(),
                                        this.getTransactionActivatedData().getFaultCodeString(),
                                        this.getCreationDate(),
                                        status
                                ),
                                new TransactionAuthorizationRequestedEvent(
                                        this.getTransactionId().value().toString(),
                                        this.getRptId().value(),
                                        this.getTransactionActivatedData().getPaymentToken(),
                                        this.getTransactionAuthorizationRequestData()
                                )
                        ),
                        new TransactionAuthorizationStatusUpdatedEvent(
                                this.getTransactionId().value().toString(),
                                this.getRptId().value(),
                                this.getTransactionActivatedData().getPaymentToken(),
                                this.getTransactionAuthorizationStatusUpdateData()
                        )
                ),
                this.getEvent()
        );
    }
}
