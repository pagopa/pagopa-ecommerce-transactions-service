package it.pagopa.transactions.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TransactionDescriptionTest {
    @Test
    void shouldConstructTransactionDescription() {
        String rawDescription = "description";
        TransactionDescription transactionDescription = new TransactionDescription(rawDescription);

        assertEquals(rawDescription, transactionDescription.value());
    }
}
