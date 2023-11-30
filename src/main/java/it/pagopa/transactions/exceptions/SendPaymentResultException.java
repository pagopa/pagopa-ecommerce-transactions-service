package it.pagopa.transactions.exceptions;

public class SendPaymentResultException extends RuntimeException {
    public final Throwable cause;

    public SendPaymentResultException(Throwable cause) {
        super("Got error during sendPaymentResult of type with cause: %s".formatted(cause));
        this.cause = cause;
    }
}
