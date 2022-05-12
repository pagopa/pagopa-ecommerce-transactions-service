package it.pagopa.transactions.documents;

import com.azure.spring.data.cosmos.core.mapping.PartitionKey;
import it.pagopa.transactions.utils.TransactionEventCode;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.UUID;

import static java.time.ZonedDateTime.now;

@Data
@Document(collection = "eventstore")
public class TransactionEvent<T> {

    @Id
    private String id;
    @PartitionKey
    private String rptId;
    private String paymentToken;
    private TransactionEventCode eventCode;
    private String creationDate;
    private T data;

    public TransactionEvent(String rptId, String paymentToken, TransactionEventCode eventCode, T data) {
        this.id = UUID.randomUUID().toString();
        this.eventCode = eventCode;
        this.paymentToken = paymentToken;
        this.data = data;
        this.creationDate = now().toString();
    }
}
