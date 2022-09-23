package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionInitEvent;
import lombok.EqualsAndHashCode;

import java.time.ZonedDateTime;
import java.util.UUID;

@EqualsAndHashCode
public final class EmptyTransaction implements EventUpdatable<TransactionInitialized, TransactionInitEvent>, Transaction {
    @Override
    public TransactionInitialized apply(TransactionInitEvent event) {
        return new TransactionInitialized(
                new TransactionId(UUID.fromString(event.getTransactionId())),
                new PaymentToken(event.getPaymentToken()),
                new RptId(event.getRptId()),
                new TransactionDescription(event.getData().getDescription()),
                new TransactionAmount(event.getData().getAmount()),
                new Email(event.getData().getEmail()),
                ZonedDateTime.parse(event.getCreationDate()),
                TransactionStatusDto.INITIALIZED
        );
    }

    @Override
    public <E> Transaction applyEvent(E event) {
        if (event instanceof TransactionInitEvent) {
            return this.apply((TransactionInitEvent) event);
        } else {
            return this;
        }
    }
}
