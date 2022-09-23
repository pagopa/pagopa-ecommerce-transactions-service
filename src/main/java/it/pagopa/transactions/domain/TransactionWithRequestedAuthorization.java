package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.transactions.domain.pojos.BaseTransaction;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithRequestedAuthorization;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
public final class TransactionWithRequestedAuthorization extends BaseTransactionWithRequestedAuthorization implements EventUpdatable<TransactionWithCompletedAuthorization, TransactionAuthorizationStatusUpdatedEvent>, Transaction {
    TransactionWithRequestedAuthorization(BaseTransaction transaction, TransactionAuthorizationRequestedEvent event) {
        super(transaction, event.getData());
    }

    @Override
    public TransactionWithCompletedAuthorization apply(TransactionAuthorizationStatusUpdatedEvent event) {
        return new TransactionWithCompletedAuthorization(this.withStatus(event.getData().getNewTransactionStatus()), event);
    }

    @Override
    public <E> Transaction applyEvent(E event) {
        if (event instanceof TransactionAuthorizationStatusUpdatedEvent) {
            return this.apply((TransactionAuthorizationStatusUpdatedEvent) event);
        } else {
            return this;
        }
    }

    @Override
    public TransactionWithRequestedAuthorization withStatus(TransactionStatusDto status) {
        return new TransactionWithRequestedAuthorization(
                new TransactionInitialized(
                        this.getTransactionId(),
                        this.getPaymentToken(),
                        this.getRptId(),
                        this.getDescription(),
                        this.getAmount(),
                        this.getEmail(),
                        this.getCreationDate(),
                        status
                ),
                new TransactionAuthorizationRequestedEvent(
                        this.getTransactionId().value().toString(),
                        this.getRptId().value(),
                        this.getPaymentToken().value(),
                        this.getTransactionAuthorizationRequestData()
                )
        );
    }
}
