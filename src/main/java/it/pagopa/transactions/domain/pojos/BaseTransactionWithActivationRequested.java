package it.pagopa.transactions.domain.pojos;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionActivatedData;
import it.pagopa.transactions.documents.TransactionActivatedEvent;
import it.pagopa.transactions.documents.TransactionActivationRequestedData;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdateData;
import it.pagopa.transactions.domain.*;

import java.time.ZonedDateTime;

public abstract class BaseTransactionWithActivationRequested extends BaseTransaction{

    TransactionActivatedData transactionActivationRequestedData;


    public BaseTransactionWithActivationRequested(BaseTransaction baseTransaction, TransactionActivatedEvent transactionActivatedEvent) {
        super(baseTransaction.getTransactionId(), baseTransaction.getPaymentToken(), baseTransaction.getRptId(), baseTransaction.getDescription(), baseTransaction.getAmount(), baseTransaction.getCreationDate(), baseTransaction.getStatus());
        this.transactionActivationRequestedData = transactionActivatedEvent.getData();
    }
}
