package it.pagopa.transactions.commands;

import lombok.Getter;

@Getter
public enum TransactionsCommandCode {

    ACTIVATE("ACTIVATE"),
    REQUEST_AUTHORIZATION("REQUEST_AUTHORIZATION"),
    UPDATE_AUTHORIZATION_STATUS("UPDATE_AUTHORIZATION_STATUS"),
    SEND_CLOSURE("SEND_CLOSURE"),
    SEND_CLOSURE_REQUEST("SEND_CLOSURE_REQUEST"),
    UPDATE_TRANSACTION_STATUS("UPDATE_TRANSACTION_STATUS"),
    USER_CANCEL_REQUEST("USER_CANCEL_REQUEST");

    private final String code;

    TransactionsCommandCode(final String code) {
        this.code = code;
    }
}
