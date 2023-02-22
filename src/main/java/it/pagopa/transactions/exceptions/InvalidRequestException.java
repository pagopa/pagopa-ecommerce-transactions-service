package it.pagopa.transactions.exceptions;

public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(
            String message,
            Throwable t
    ) {
        super(message, t);
    }

}
