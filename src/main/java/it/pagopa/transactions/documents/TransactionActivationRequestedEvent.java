package it.pagopa.transactions.documents;

import it.pagopa.transactions.utils.TransactionEventCode;
import lombok.Generated;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "eventstore")
@Generated
public final class TransactionActivationRequestedEvent extends TransactionEvent<TransactionActivationRequestedData> {
    public TransactionActivationRequestedEvent(String transactionId, String rptId, String creationDate, TransactionActivationRequestedData data) {
        super(transactionId, rptId, null, TransactionEventCode.TRANSACTION_ACTIVATION_REQUESTED_EVENT, creationDate, data);
    }

    public TransactionActivationRequestedEvent(String transactionId, String rptId, TransactionActivationRequestedData data) {
        super(transactionId, rptId, null, TransactionEventCode.TRANSACTION_ACTIVATION_REQUESTED_EVENT, data);
    }
}
