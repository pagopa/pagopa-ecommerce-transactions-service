package it.pagopa.transactions.commands;

import lombok.Getter;

@Getter
public enum TransactionsCommandCode {

  INITIALIZE_TRANSACTION("INITIALIZE_TRANSACTION"),
  REQUEST_AUTHORIZATION("REQUEST_AUTHORIZATION");

  private final String code;

  TransactionsCommandCode(final String code) {
    this.code = code;
  }
}
