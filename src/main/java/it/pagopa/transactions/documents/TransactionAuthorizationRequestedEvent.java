package it.pagopa.transactions.documents;

import it.pagopa.transactions.utils.TransactionEventCode;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "eventstore")
public final class TransactionAuthorizationRequestedEvent extends TransactionEvent<TransactionAuthorizationRequestData> {
    public TransactionAuthorizationRequestedEvent(String rptId, String paymentToken, TransactionAuthorizationRequestData data) {
        super(rptId, paymentToken, TransactionEventCode.TRANSACTION_AUTHORIZATION_REQUESTED_EVENT, data);
    }
}
