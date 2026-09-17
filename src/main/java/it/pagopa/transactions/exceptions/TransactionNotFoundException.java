package it.pagopa.transactions.exceptions;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@Getter
@AllArgsConstructor
@ResponseStatus(value = HttpStatus.NOT_FOUND)
public class TransactionNotFoundException extends RuntimeException {
    private final String transactionId;
    private final UUID userId;

    public TransactionNotFoundException(String transactionId) {
        this.transactionId = transactionId;
        this.userId = null;
    }
}
