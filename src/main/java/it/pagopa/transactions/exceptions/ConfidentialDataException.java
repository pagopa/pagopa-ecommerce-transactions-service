package it.pagopa.transactions.exceptions;

public class ConfidentialDataException extends RuntimeException {

    public ConfidentialDataException(Exception e) {
        super("Exception during confidential data encrypt/decrypt", e);
    }
}
