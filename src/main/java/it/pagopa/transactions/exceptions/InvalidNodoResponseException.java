package it.pagopa.transactions.exceptions;

import lombok.Getter;

public class InvalidNodoResponseException extends RuntimeException {

    @Getter
    private final String errorDescription;

    public InvalidNodoResponseException(String errorDescription) {
        super("Invalid Nodo response received. Cause: ".concat(errorDescription));
        this.errorDescription = errorDescription;
    }
}
