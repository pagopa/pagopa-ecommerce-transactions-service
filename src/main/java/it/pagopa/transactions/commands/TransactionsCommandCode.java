package it.pagopa.transactions.commands;

public enum TransactionsCommandCode {

  INITIALIZE_TRANSACTION("INITIALIZE_TRANSACTION");

  private final String code;

  TransactionsCommandCode(final String code) {
    this.code = code;
  }

  @Override
  public String toString() {
    return code;
  }
}
