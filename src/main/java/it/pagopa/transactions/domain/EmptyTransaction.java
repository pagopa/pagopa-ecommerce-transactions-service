package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionActivatedEvent;
import lombok.EqualsAndHashCode;

import java.time.ZonedDateTime;
import java.util.UUID;

@EqualsAndHashCode
public final class EmptyTransaction implements EventUpdatable<TransactionActivated, TransactionActivatedEvent>, Transaction {
    @Override
    public TransactionActivated apply(TransactionActivatedEvent event) {
        return new TransactionActivated(
                new TransactionId(UUID.fromString(event.getTransactionId())),
                new PaymentToken(event.getPaymentToken()),
                new RptId(event.getRptId()),
                new TransactionDescription(event.getData().getDescription()),
                new TransactionAmount(event.getData().getAmount()),
                ZonedDateTime.parse(event.getCreationDate()),
                TransactionStatusDto.ACTIVATED
        );
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
