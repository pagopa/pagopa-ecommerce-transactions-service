package it.pagopa.transactions.documents;

import java.time.ZonedDateTime;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import it.pagopa.transactions.utils.TransactionStatus;
import lombok.Data;

import static java.time.ZonedDateTime.now;

@Data
@Document
public class Transaction {

    @Id
    private String id;
    private String rptId;
    private String paymentToken;
    private String description;
    private int amount;
    private TransactionStatus status;
    private ZonedDateTime creationDate;

    public Transaction(String paymentToken, String rptId, String description, int amount, TransactionStatus status) {
        this.id = UUID.randomUUID().toString();
        this.rptId = rptId;
        this.description = description;
        this.paymentToken = paymentToken;
        this.amount = amount;
        this.status = status;
        this.creationDate = now();
    }
}
