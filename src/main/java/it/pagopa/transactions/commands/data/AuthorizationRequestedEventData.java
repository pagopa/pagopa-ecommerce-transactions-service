package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent;

public record AuthorizationRequestedEventData(
        AuthorizationRequestData authorizationRequestData,
        TransactionAuthorizationRequestedEvent event
) {
}
