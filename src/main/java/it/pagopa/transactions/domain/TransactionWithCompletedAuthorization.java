package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.transactions.documents.TransactionClosureSentEvent;
import it.pagopa.transactions.domain.pojos.BaseTransaction;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithCompletedAuthorization;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithRequestedAuthorization;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
public final class TransactionWithCompletedAuthorization extends BaseTransactionWithCompletedAuthorization implements EventUpdatable<TransactionClosed, TransactionClosureSentEvent>, Transaction {
    public TransactionWithCompletedAuthorization(TransactionWithRequestedAuthorization transaction, TransactionAuthorizationStatusUpdatedEvent event) {
        super(transaction, event.getData());
    }

    @Override
    public TransactionClosed apply(TransactionClosureSentEvent event) {
        return new TransactionClosed(this.withStatus(event.getData().getNewTransactionStatus()), event);
    }

    @Override
    public <E> Transaction applyEvent(E event) {
        if (event instanceof TransactionClosureSentEvent) {
            return this.apply((TransactionClosureSentEvent) event);
        } else {
            return this;
        }
    }

    @Override
    public TransactionWithCompletedAuthorization withStatus(TransactionStatusDto status) {
        return new TransactionWithCompletedAuthorization(
                new TransactionWithRequestedAuthorization(
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
                ),
                new TransactionAuthorizationStatusUpdatedEvent(
                        this.getTransactionId().value().toString(),
                        this.getRptId().value(),
                        this.getPaymentToken().value(),
                        this.getTransactionAuthorizationStatusUpdateData()
                )
        );
    }
}
