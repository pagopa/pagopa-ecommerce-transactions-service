package it.pagopa.transactions.documents;

import it.pagopa.transactions.utils.TransactionEventCode;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "eventstore")
public final class TransactionClosureRequestedEvent extends TransactionEvent<TransactionClosureRequestData> {
    public TransactionClosureRequestedEvent(String rptId, String paymentToken, TransactionClosureRequestData data) {
        super(rptId, paymentToken, TransactionEventCode.TRANSACTION_OUTCOME_UPDATED_EVENT, data);
    }
}
