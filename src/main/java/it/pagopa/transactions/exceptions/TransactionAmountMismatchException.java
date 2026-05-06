package it.pagopa.transactions.exceptions;

import lombok.Getter;

@Getter
public class TransactionAmountMismatchException extends RuntimeException {

    private Long requestAmount;

    private Long transactionAmount;

    public TransactionAmountMismatchException(
            Long requestAmount,
            Long transactionAmount
    ) {
        super("Transaction amount mismatch");
        this.requestAmount = requestAmount;
        this.transactionAmount = transactionAmount;
    }

}
