package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.transactions.documents.TransactionClosureSendData;
import it.pagopa.transactions.documents.TransactionClosureSentEvent;
import it.pagopa.transactions.domain.pojos.BaseTransaction;
import it.pagopa.transactions.domain.pojos.BaseTransactionClosed;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithCompletedAuthorization;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
public final class TransactionClosed extends BaseTransactionClosed implements Transaction {
    public TransactionClosed(TransactionWithCompletedAuthorization transaction, TransactionClosureSentEvent event) {
        super(transaction, event.getData());
    }

    @Override
    public <E> Transaction applyEvent(E event) {
        return this;
    }

    @Override
    public TransactionClosed withStatus(TransactionStatusDto status) {
        return new TransactionClosed(
                new TransactionWithCompletedAuthorization(
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
                ),
                new TransactionClosureSentEvent(
                        this.getTransactionId().value().toString(),
                        this.getRptId().value(),
                        this.getPaymentToken().value(),
                        this.getTransactionClosureSendData()
                )
        );
    }
}
