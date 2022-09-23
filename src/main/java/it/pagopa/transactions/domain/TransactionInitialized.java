package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.annotations.AggregateRoot;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.domain.pojos.BaseTransaction;
import lombok.EqualsAndHashCode;

import java.time.ZonedDateTime;
import java.util.Optional;

import static java.time.ZonedDateTime.now;

@EqualsAndHashCode(callSuper = true)
public final class TransactionInitialized extends BaseTransaction implements EventUpdatable<TransactionWithRequestedAuthorization, TransactionAuthorizationRequestedEvent>, Transaction {
    public TransactionInitialized(TransactionId transactionId, PaymentToken paymentToken, RptId rptId, TransactionDescription description, TransactionAmount amount, Email email, ZonedDateTime creationDate, TransactionStatusDto status) {
        super(transactionId, paymentToken, rptId, description, amount, email, creationDate, status);
    }

    public TransactionInitialized(TransactionId transactionId, PaymentToken paymentToken, RptId rptId, TransactionDescription description, TransactionAmount amount, Email email, TransactionStatusDto status) {
        super(transactionId, paymentToken, rptId, description, amount, email, now(), status);
    }

    @Override
    public TransactionWithRequestedAuthorization apply(TransactionAuthorizationRequestedEvent event) {
        return new TransactionWithRequestedAuthorization(this.withStatus(TransactionStatusDto.AUTHORIZATION_REQUESTED), event);
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
    public TransactionInitialized withStatus(TransactionStatusDto status) {
        return new TransactionInitialized(
                this.getTransactionId(),
                this.getPaymentToken(),
                this.getRptId(),
                this.getDescription(),
                this.getAmount(),
                this.getEmail(),
                this.getCreationDate(),
                status
        );
    }
}
