package it.pagopa.transactions.commands;

import lombok.Getter;

@Getter
public enum TransactionsCommandCode {

  INITIALIZE_TRANSACTION("INITIALIZE_TRANSACTION"),
  AUTHORIZE_TRANSACTION("AUTHORIZE_TRANSACTION");

  private final String code;

  TransactionsCommandCode(final String code) {
    this.code = code;
  }
}
