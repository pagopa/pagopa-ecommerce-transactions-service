package it.pagopa.transactions.model;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.annotations.AggregateRoot;
import it.pagopa.transactions.annotations.AggregateRootId;
import lombok.Data;

import java.time.ZonedDateTime;

import static java.time.ZonedDateTime.now;

@AggregateRoot
@Data
public class Transaction {
    @AggregateRootId
    private final TransactionId transactionId;

    private final PaymentToken paymentToken;
    private final RptId rptId;
    private final TransactionDescription description;
    private final TransactionAmount amount;
    private final ZonedDateTime creationDate;

    private TransactionStatusDto status;

    public Transaction(TransactionId transactionId, PaymentToken paymentToken, RptId rptId, TransactionDescription description, TransactionAmount amount, TransactionStatusDto status) {
        this.transactionId = transactionId;
        this.rptId = rptId;
        this.description = description;
        this.paymentToken = paymentToken;
        this.amount = amount;
        this.status = status;
        this.creationDate = now();
    }
}
