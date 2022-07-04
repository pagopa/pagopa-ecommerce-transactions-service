package it.pagopa.transactions.documents;

import it.pagopa.transactions.utils.TransactionEventCode;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "eventstore")
public final class TransactionClosureRequestedEvent extends TransactionEvent<TransactionClosureRequestData> {
    public TransactionClosureRequestedEvent(String transactionId, String rptId, String paymentToken, TransactionClosureRequestData data) {
        super(transactionId, rptId, paymentToken, TransactionEventCode.TRANSACTION_CLOSURE_REQUESTED_EVENT, data);
    }
}
