package it.pagopa.transactions.documents;

import it.pagopa.transactions.server.model.TransactionStatusDto;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import static java.time.ZonedDateTime.now;

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
        this.rptId = rptId;
        this.description = description;
        this.paymentToken = paymentToken;
        this.amount = amount;
        this.status = status;
        this.creationDate = now().toString();
    }
}
