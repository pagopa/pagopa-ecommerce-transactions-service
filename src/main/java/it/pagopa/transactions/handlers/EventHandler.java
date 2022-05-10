package it.pagopa.transactions.handlers;

import it.pagopa.transactions.documents.TransactionEvent;

public interface EventHandler<T, S> {

    S handle(TransactionEvent<T> event) throws Exception; // TODO specify exception
}
