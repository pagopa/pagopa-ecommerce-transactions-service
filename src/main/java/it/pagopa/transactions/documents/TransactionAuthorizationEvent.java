package it.pagopa.transactions.documents;

import it.pagopa.transactions.utils.TransactionEventCode;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "eventstore")
public final class TransactionAuthorizationEvent extends TransactionEvent<TransactionAuthorizationData> {
    TransactionAuthorizationEvent(String rptId, String paymentToken, TransactionAuthorizationData data) {
        super(rptId, paymentToken, TransactionEventCode.TRANSACTION_AUTHORIZED_EVENT, data);
    }
}
