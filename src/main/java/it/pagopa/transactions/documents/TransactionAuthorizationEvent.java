package it.pagopa.transactions.documents;

import it.pagopa.transactions.utils.TransactionEventCode;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "eventstore")
public final class TransactionAuthorizationEvent extends TransactionEvent<TransactionAuthorizationData> {
    public TransactionAuthorizationEvent(String rptId, String paymentToken, TransactionAuthorizationData data) {
        super(rptId, paymentToken, TransactionEventCode.TRANSACTION_AUTHORIZATION_REQUESTED_EVENT, data);
    }
}
