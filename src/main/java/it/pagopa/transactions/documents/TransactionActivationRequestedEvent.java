package it.pagopa.transactions.documents;

import it.pagopa.transactions.utils.TransactionEventCode;
import lombok.Generated;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "eventstore")
@Generated
@NoArgsConstructor
@ToString(callSuper = true)
public final class TransactionActivationRequestedEvent extends TransactionEvent<TransactionActivationRequestedData> {
    public TransactionActivationRequestedEvent(String transactionId, String rptId, String creationDate, TransactionActivationRequestedData data) {
        super(transactionId, rptId, null, TransactionEventCode.TRANSACTION_ACTIVATION_REQUESTED_EVENT, creationDate, data);
    }

    public TransactionActivationRequestedEvent(String transactionId, String rptId, TransactionActivationRequestedData data) {
        super(transactionId, rptId, null, TransactionEventCode.TRANSACTION_ACTIVATION_REQUESTED_EVENT, data);
    }
}
