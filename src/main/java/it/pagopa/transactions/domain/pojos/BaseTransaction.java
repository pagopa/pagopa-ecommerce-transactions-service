package it.pagopa.transactions.domain.pojos;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.annotations.AggregateRootId;
import it.pagopa.transactions.domain.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.ZonedDateTime;

/**
 * POJO meant to serve as a base layer for transaction attributes
 */
@ToString
@EqualsAndHashCode
@AllArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Getter
public abstract class BaseTransaction {
    @AggregateRootId
    TransactionId transactionId;
    PaymentToken paymentToken;
    RptId rptId;
    TransactionDescription description;
    TransactionAmount amount;
    ZonedDateTime creationDate;

    TransactionStatusDto status;
}
