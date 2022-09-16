package it.pagopa.transactions.domain;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

class TransactionTest {
    @Test
    void shouldConstructTransaction() {
        TransactionId transactionId = new TransactionId(UUID.fromString("833d303a-f857-11ec-b939-0242ac120002"));
        PaymentToken paymentToken = new PaymentToken("");
        RptId rptId = new RptId("77777777777302016723749670035");
        TransactionDescription description = new TransactionDescription("");
        TransactionAmount amount = new TransactionAmount(100);
        TransactionStatusDto status = TransactionStatusDto.INITIALIZED;

        TransactionInitialized transaction = new TransactionInitialized(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                status
        );

        assertEquals(transaction.getPaymentToken(), paymentToken);
        assertEquals(transaction.getRptId(), rptId);
        assertEquals(transaction.getDescription(), description);
        assertEquals(transaction.getAmount(), amount);
        assertEquals(transaction.getStatus(), status);
    }
}
