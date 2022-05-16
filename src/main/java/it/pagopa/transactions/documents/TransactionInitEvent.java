package it.pagopa.transactions.documents;

import it.pagopa.transactions.utils.TransactionEventCode;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "eventstore")
public final class TransactionInitEvent extends TransactionEvent<TransactionInitData> {
    public TransactionInitEvent(String rptId, String paymentToken, TransactionInitData data) {
        super(rptId, paymentToken, TransactionEventCode.TRANSACTION_INITIALIZED_EVENT, data);
    }
}
