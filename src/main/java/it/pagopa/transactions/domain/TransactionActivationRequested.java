package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionActivatedEvent;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.domain.pojos.BaseTransaction;

import java.time.ZonedDateTime;

import static java.time.ZonedDateTime.now;

public final class TransactionActivationRequested extends BaseTransaction implements EventUpdatable<TransactionActivated, TransactionActivatedEvent>, Transaction {
    public TransactionActivationRequested(TransactionId transactionId, RptId rptId, TransactionDescription description, TransactionAmount amount, ZonedDateTime creationDate, TransactionStatusDto status) {
        super(transactionId, rptId, description, amount, creationDate, status);
    }

    public TransactionActivationRequested(TransactionId transactionId, RptId rptId, TransactionDescription description, TransactionAmount amount, TransactionStatusDto status) {
        super(transactionId, rptId, description, amount, now(), status);
    }

    @Override
    public TransactionActivated apply(TransactionActivatedEvent event) {
        return new TransactionActivated(this, event);
    }

    @Override
    public <E> Transaction applyEvent(E event) {
        if (event instanceof TransactionActivatedEvent) {
            return this.apply((TransactionActivatedEvent) event);
        } else {
            return this;
        }
    }

    @Override
    public Transaction applyEvent2(TransactionEvent<?> event) {
        if (event instanceof TransactionActivatedEvent) {
            return this.apply((TransactionActivatedEvent) event);
        } else {
            return this;
        }
    }
}
