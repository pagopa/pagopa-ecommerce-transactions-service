package it.pagopa.transactions.utils;

public enum TransactionStatus {

  TRANSACTION_INITIALIZED("TRANSACTION_INITIALIZED");

  private final String code;

  TransactionStatus(final String code) {
    this.code = code;
  }

  public String getCode() {
    return code;
  }
  
}
