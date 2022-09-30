package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionActivatedData;
import it.pagopa.transactions.documents.TransactionActivatedEvent;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithPaymentToken;

import java.time.ZonedDateTime;

import static java.time.ZonedDateTime.now;

public final class TransactionActivated extends BaseTransactionWithPaymentToken implements EventUpdatable<TransactionWithRequestedAuthorization, TransactionAuthorizationRequestedEvent>, Transaction {
    public TransactionActivated(TransactionId transactionId, PaymentToken paymentToken, RptId rptId, TransactionDescription description, TransactionAmount amount, ZonedDateTime creationDate, TransactionStatusDto status) {
        //FIXME Fix null value after PR EMail merged
        super(new TransactionActivationRequested(transactionId, rptId, description, amount, creationDate, status), new TransactionActivatedData(description.value(), amount.value(), null, null, null, paymentToken.value()));
    }

    public TransactionActivated(TransactionId transactionId, PaymentToken paymentToken, RptId rptId, TransactionDescription description, TransactionAmount amount, TransactionStatusDto status) {
        //FIXME Fix null value after PR EMail merged
        super(new TransactionActivationRequested(transactionId, rptId, description, amount, now(), status), new TransactionActivatedData(description.value(), amount.value(), null, null, null, paymentToken.value()));
    }

    public TransactionActivated(TransactionActivationRequested transactionActivationRequested, TransactionActivatedEvent event) {
        super(transactionActivationRequested, event.getData());
    }

    @Override
    public TransactionWithRequestedAuthorization apply(TransactionAuthorizationRequestedEvent event) {
        return new TransactionWithRequestedAuthorization(this, event);
    }

    @Override
    public <E> Transaction applyEvent(E event) {
        if (event instanceof TransactionAuthorizationRequestedEvent) {
            return this.apply((TransactionAuthorizationRequestedEvent) event);
        } else {
            return this;
        }
    }

    @Override
    public Transaction applyEvent2(TransactionEvent<?> event) {
        if (event instanceof TransactionAuthorizationRequestedEvent) {
            return this.apply((TransactionAuthorizationRequestedEvent) event);
        } else {
            return this;
        }
    }
}
