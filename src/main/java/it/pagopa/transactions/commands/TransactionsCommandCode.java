package it.pagopa.transactions.commands;

import lombok.Getter;

@Getter
public enum TransactionsCommandCode {

    ACTIVATE("ACTIVATE"),
    REQUEST_AUTHORIZATION("REQUEST_AUTHORIZATION"),
    UPDATE_AUTHORIZATION_STATUS("UPDATE_AUTHORIZATION_STATUS"),
    SEND_CLOSURE("SEND_CLOSURE"),
    UPDATE_TRANSACTION_STATUS("UPDATE_TRANSACTION_STATUS");

    private final String code;

    TransactionsCommandCode(final String code) {
        this.code = code;
    }
}
