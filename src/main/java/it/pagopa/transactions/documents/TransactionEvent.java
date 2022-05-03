package it.pagopa.transactions.documents;

import java.time.ZonedDateTime;
import java.util.UUID;

import com.azure.spring.data.cosmos.core.mapping.PartitionKey;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import it.pagopa.transactions.utils.TransactionEventCode;
import lombok.Data;

import static java.time.ZonedDateTime.now;

@Data
@Document
public class TransactionEvent<T> {

    @Id
    private String id;
    @PartitionKey
    private String rptId;
    private String paymentToken;
    private TransactionEventCode eventCode;
    private ZonedDateTime creationDate;
    private T data;

    public TransactionEvent(String rptId, String paymentToken, TransactionEventCode eventCode, T data) {
        this.id = UUID.randomUUID().toString();
        this.eventCode = eventCode;
        this.paymentToken = paymentToken;
        this.data = data;
        this.creationDate = now();
    }
}
