package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent;

import java.time.Duration;

public record ClosureRequestedEventData(
        Duration visibilityTimeout,
        TransactionClosureRequestedEvent transactionClosureRequestedEvent
) {
}
