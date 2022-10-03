package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionActivatedData;
import it.pagopa.transactions.documents.TransactionActivatedEvent;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.domain.pojos.BaseTransaction;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithPaymentToken;

import java.time.ZonedDateTime;

import static java.time.ZonedDateTime.now;

public final class TransactionActivated extends BaseTransactionWithPaymentToken implements EventUpdatable<TransactionWithRequestedAuthorization, TransactionAuthorizationRequestedEvent>, Transaction {
    public TransactionActivated(TransactionId transactionId, PaymentToken paymentToken, RptId rptId, TransactionDescription description, TransactionAmount amount, Email email, ZonedDateTime creationDate, TransactionStatusDto status) {
        super(new TransactionActivationRequested(transactionId, rptId, description, amount, email, creationDate, status), new TransactionActivatedData(description.value(), amount.value(), email.value(), null, null, paymentToken.value()));
    }

    public TransactionActivated(TransactionId transactionId, PaymentToken paymentToken, RptId rptId, TransactionDescription description, TransactionAmount amount, Email email, TransactionStatusDto status) {
        super(new TransactionActivationRequested(transactionId, rptId, description, amount, email, now(), status), new TransactionActivatedData(description.value(), amount.value(), email.value(), null, null, paymentToken.value()));
    }

    public TransactionActivated(TransactionActivationRequested transactionActivationRequested, TransactionActivatedEvent event) {
        super(transactionActivationRequested, event.getData());
    }

    @Override
    public TransactionWithRequestedAuthorization apply(TransactionAuthorizationRequestedEvent event) {
        return new TransactionWithRequestedAuthorization(this, event);
    }

    @Override
    public Transaction applyEvent(TransactionEvent<?> event) {
        if (event instanceof TransactionAuthorizationRequestedEvent) {
            return this.apply((TransactionAuthorizationRequestedEvent) event);
        } else {
            return this;
        }
    }

    @Override
    public TransactionActivated withStatus(TransactionStatusDto status) {
        return new TransactionActivated(
                this.getTransactionId(),
                new PaymentToken(this.getTransactionActivatedData().getPaymentToken()),
                this.getRptId(),
                this.getDescription(),
                this.getAmount(),
                this.getEmail(),
                this.getCreationDate(),
                this.getStatus()
        );
    }
}
