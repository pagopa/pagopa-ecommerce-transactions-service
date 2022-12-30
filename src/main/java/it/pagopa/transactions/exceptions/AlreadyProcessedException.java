package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.domain.TransactionId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(value = HttpStatus.CONFLICT)
public class AlreadyProcessedException extends Exception {
    private final String transactionId;

    public AlreadyProcessedException(TransactionId transactionId) {
        this.transactionId = transactionId.value().toString();
    }

    public String getTransactionId() {
        return transactionId;
    }
}
