package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
public class LockNotAcquiredException extends RuntimeException {

    private final TransactionId transactionId;

    private final List<ExclusiveLockDocument> exclusiveLockDocumentList;

    public LockNotAcquiredException(
            TransactionId transactionId,
            ExclusiveLockDocument exclusiveLockDocument
    ) {
        super(
                "Lock not acquired for transaction with id: [%s] and locking key: [%s]"
                        .formatted(transactionId.value(), exclusiveLockDocument.id())
        );
        this.exclusiveLockDocumentList = List.of(exclusiveLockDocument);
        this.transactionId = transactionId;
    }

    public LockNotAcquiredException(
            TransactionId transactionId,
            List<ExclusiveLockDocument> exclusiveLockDocumentList
    ) {
        super(
                "Lock not acquired for transaction with id: [%s] and locking key: [%s]"
                        .formatted(
                                transactionId.value(),
                                exclusiveLockDocumentList.stream().map(ExclusiveLockDocument::id)
                                        .collect(Collectors.joining(", "))
                        )
        );
        this.exclusiveLockDocumentList = exclusiveLockDocumentList;
        this.transactionId = transactionId;
    }

}
