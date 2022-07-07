package it.pagopa.transactions.commands;

import lombok.Getter;

@Getter
public enum TransactionsCommandCode {

  INITIALIZE_TRANSACTION("INITIALIZE_TRANSACTION"),
  REQUEST_AUTHORIZATION("REQUEST_AUTHORIZATION"),
  UPDATE_AUTHORIZATION_STATUS("UPDATE_AUTHORIZATION_STATUS"),

  SEND_CLOSURE("SEND_CLOSURE");

  private final String code;

  TransactionsCommandCode(final String code) {
    this.code = code;
  }
}
