package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.annotations.AggregateRoot;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.domain.pojos.BaseTransaction;

import java.time.ZonedDateTime;

import static java.time.ZonedDateTime.now;

@AggregateRoot
public class Transaction extends BaseTransaction implements EventUpdatable<TransactionWithRequestedAuthorization, TransactionAuthorizationRequestedEvent> {
    public Transaction(TransactionId transactionId, PaymentToken paymentToken, RptId rptId, TransactionDescription description, TransactionAmount amount, ZonedDateTime creationDate, TransactionStatusDto status) {
        super(transactionId, paymentToken, rptId, description, amount, creationDate, status);
    }

    public Transaction(TransactionId transactionId, PaymentToken paymentToken, RptId rptId, TransactionDescription description, TransactionAmount amount, TransactionStatusDto status) {
        super(transactionId, paymentToken, rptId, description, amount, now(), status);
    }

    @Override
    public TransactionWithRequestedAuthorization applyEvent(TransactionAuthorizationRequestedEvent event) {
        return new TransactionWithRequestedAuthorization(this, event);
    }
}
