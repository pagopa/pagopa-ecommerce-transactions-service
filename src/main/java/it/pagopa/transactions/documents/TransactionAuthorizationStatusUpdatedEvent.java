package it.pagopa.transactions.documents;

import it.pagopa.transactions.utils.TransactionEventCode;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "eventstore")
public final class TransactionAuthorizationStatusUpdatedEvent extends TransactionEvent<TransactionAuthorizationStatusUpdateData> {
    public TransactionAuthorizationStatusUpdatedEvent(String rptId, String paymentToken, TransactionAuthorizationStatusUpdateData data) {
        super(rptId, paymentToken, TransactionEventCode.TRANSACTION_AUTHORIZATION_STATUS_UPDATED_EVENT, data);
    }
}
