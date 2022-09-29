package it.pagopa.transactions.domain.pojos;

import it.pagopa.transactions.documents.TransactionActivatedData;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@ToString
@EqualsAndHashCode(callSuper = true)
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Getter
public abstract class BaseTransactionWithActivationRequested extends BaseTransaction {

  TransactionActivatedData transactionActivationRequestedData;

  protected BaseTransactionWithActivationRequested(
      BaseTransaction baseTransaction,
      TransactionActivatedData transactionActivationRequestedData) {
    super(
        baseTransaction.getTransactionId(),
        baseTransaction.getPaymentToken(),
        baseTransaction.getRptId(),
        baseTransaction.getDescription(),
        baseTransaction.getAmount(),
        baseTransaction.getCreationDate(),
        baseTransaction.getStatus());

    this.transactionActivationRequestedData = transactionActivationRequestedData;
  }
}
