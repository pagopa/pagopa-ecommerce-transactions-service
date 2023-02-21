package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.domain.v1.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.CONFLICT)
public class AlreadyProcessedException extends Exception {
    private final TransactionId transactionId;

    public AlreadyProcessedException(TransactionId transactionId) {
        this.transactionId = transactionId;
    }

    public TransactionId getTransactionId() {
        return transactionId;
    }
}
