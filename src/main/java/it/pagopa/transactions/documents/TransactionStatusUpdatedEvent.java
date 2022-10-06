package it.pagopa.transactions.documents;

import it.pagopa.transactions.utils.TransactionEventCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "eventstore")
@NoArgsConstructor
@ToString(callSuper = true)
public final class TransactionStatusUpdatedEvent extends TransactionEvent<TransactionStatusUpdateData> {
    public TransactionStatusUpdatedEvent(String transactionId, String rptId, String paymentToken, TransactionStatusUpdateData data) {
        super(transactionId, rptId, paymentToken, TransactionEventCode.TRANSACTION_STATUS_UPDATED_EVENT, data);
    }
}
