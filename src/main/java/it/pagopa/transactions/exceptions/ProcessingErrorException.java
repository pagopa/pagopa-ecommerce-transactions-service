package it.pagopa.transactions.exceptions;

public class ProcessingErrorException extends RuntimeException {

    public ProcessingErrorException(String message) {
        super(message);
    }

}
