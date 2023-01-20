package it.pagopa.transactions.exceptions;

public class JWTTokenGenerationException extends Exception {

    public JWTTokenGenerationException() {
        super("JWT token generation error");
    }
}
