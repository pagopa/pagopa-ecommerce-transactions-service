package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument;
import lombok.Getter;

@Getter
public class LockNotAcquiredException extends RuntimeException {

    private final TransactionId transactionId;

    private final ExclusiveLockDocument exclusiveLockDocument;

    public LockNotAcquiredException(
            TransactionId transactionId,
            ExclusiveLockDocument exclusiveLockDocument
    ) {
        super(
                "Lock not acquired for transaction with id: [%s] and locking key: [%s]"
                        .formatted(transactionId.value(), exclusiveLockDocument.id())
        );
        this.exclusiveLockDocument = exclusiveLockDocument;
        this.transactionId = transactionId;
    }

}
