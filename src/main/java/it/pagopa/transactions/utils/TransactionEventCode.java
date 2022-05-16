package it.pagopa.transactions.utils;

public enum TransactionEventCode {

    TRANSACTION_INITIALIZED_EVENT("TRANSACTION_INITIALIZED_EVENT");

    private final String code;

    TransactionEventCode(final String code) {
        this.code = code;
    }

    @Override
    public String toString() {
        return code;
    }
}
