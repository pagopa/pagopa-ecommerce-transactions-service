package it.pagopa.transactions.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.NOT_FOUND)
public class TransactionNotFoundException extends RuntimeException {
    private final String paymentToken;


    public TransactionNotFoundException(String paymentToken) {
        this.paymentToken = paymentToken;
    }

    public String getPaymentToken() {
        return paymentToken;
    }
}
