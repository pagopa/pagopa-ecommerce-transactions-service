package it.pagopa.transactions.documents;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;

import java.time.ZonedDateTime;

@Data
@Document(collection = "view")
public class Transaction {
    @Id
    private String paymentToken;
    private String rptId;
    private String description;
    private int amount;
    private TransactionStatusDto status;
    private String creationDate;

    public Transaction(String paymentToken, String rptId, String description, int amount, TransactionStatusDto status) {
        this(paymentToken, rptId, description, amount, status, ZonedDateTime.now());
    }

    public Transaction(String paymentToken, String rptId, String description, int amount, TransactionStatusDto status, ZonedDateTime creationDate) {
        this.rptId = rptId;
        this.description = description;
        this.paymentToken = paymentToken;
        this.amount = amount;
        this.status = status;
        this.creationDate = creationDate.toString();
    }

    public static Transaction from(it.pagopa.transactions.model.Transaction transaction) {
        return new Transaction(
                transaction.getPaymentToken().value(),
                transaction.getRptId().value(),
                transaction.getDescription().value(),
                transaction.getAmount().value(),
                transaction.getStatus(),
                transaction.getCreationDate()
        );
    }
}
