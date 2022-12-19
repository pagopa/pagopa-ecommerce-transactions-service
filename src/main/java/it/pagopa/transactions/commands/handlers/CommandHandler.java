package it.pagopa.transactions.commands.handlers;

public interface CommandHandler<T, S> {

    S handle(T command);
}
