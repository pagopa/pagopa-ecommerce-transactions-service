package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.annotations.AggregateRoot;
import it.pagopa.transactions.documents.TransactionActivatedEvent;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.domain.pojos.BaseTransaction;

import java.time.ZonedDateTime;

import static java.time.ZonedDateTime.now;

public final class TransactionActivateRequested extends BaseTransaction implements EventUpdatable<TransactionActivated, TransactionActivatedEvent>, Transaction {
    public TransactionActivateRequested(TransactionId transactionId, PaymentToken paymentToken, RptId rptId, TransactionDescription description, TransactionAmount amount, ZonedDateTime creationDate, TransactionStatusDto status) {
        super(transactionId, paymentToken, rptId, description, amount, creationDate, status);
    }

    public TransactionActivateRequested(TransactionId transactionId, PaymentToken paymentToken, RptId rptId, TransactionDescription description, TransactionAmount amount, TransactionStatusDto status) {
        super(transactionId, paymentToken, rptId, description, amount, now(), status);
    }

    @Override
    public TransactionActivated apply(TransactionActivatedEvent event) {
        // return new TransactionActivated(this, event);
        //FIXME add implementation for transaction activated constructor starting from TransactionActivateRequested
        return null;
    }

    @Override
    public <E> Transaction applyEvent(E event) {
        if (event instanceof TransactionActivatedEvent) {
            return this.apply((TransactionActivatedEvent) event);
        } else {
            return this;
        }
    }
}