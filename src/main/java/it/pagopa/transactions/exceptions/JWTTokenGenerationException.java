package it.pagopa.transactions.exceptions;

public class JWTTokenGenerationException extends RuntimeException {

    public JWTTokenGenerationException() {
        super("JWT token generation error");
    }
}
