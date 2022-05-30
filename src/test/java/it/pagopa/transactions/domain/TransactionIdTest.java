package it.pagopa.transactions.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TransactionIdTest {
    @Test
    void shouldConstructTransactionId() {
        String rawTransactionId = "transaction_id";
        TransactionId  transactionId = new TransactionId(rawTransactionId);

        assertEquals(rawTransactionId, transactionId.value());
    }
}
