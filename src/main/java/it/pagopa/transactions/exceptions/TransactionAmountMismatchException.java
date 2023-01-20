package it.pagopa.transactions.exceptions;

import lombok.Getter;

@Getter
public class TransactionAmountMismatchException extends RuntimeException {

    private Integer requestAmount;

    private Integer transactionAmount;

    public TransactionAmountMismatchException(
            Integer requestAmount,
            Integer transactionAmount
    ) {
        super("Transaction amount mismatch");
        this.requestAmount = requestAmount;
        this.transactionAmount = transactionAmount;
    }

}
