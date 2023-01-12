package it.pagopa.transactions.exceptions;

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
