package it.pagopa.transactions.domain.pojos;

import it.pagopa.transactions.documents.TransactionClosureErrorEvent;
import it.pagopa.transactions.documents.TransactionClosureSendData;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@ToString
@EqualsAndHashCode(callSuper = true)
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Getter
public abstract class BaseTransactionWithClosureError extends BaseTransactionWithCompletedAuthorization {
    TransactionClosureErrorEvent event;

    protected BaseTransactionWithClosureError(BaseTransactionWithCompletedAuthorization baseTransaction, TransactionClosureErrorEvent event) {
        super(baseTransaction, baseTransaction.getTransactionAuthorizationStatusUpdateData());
        this.event = event;
    }
}
