package it.pagopa.transactions.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.BAD_REQUEST)
public class TransactionAmountMismatchException extends RuntimeException {

    public TransactionAmountMismatchException(
            Integer requestAmount,
            Integer transactionAmount
    ) {
        super(
                "Transaction amount mismatch! Request amount: [%s], Transaction amount: [%s]"
                        .formatted(requestAmount, transactionAmount)
        );
    }

}
