
package it.pagopa.transactions.documents;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TransactionDocumentTest {

    @Test
    void shouldGetAndSetTransaction() {
        String TEST_TRANSACTIONID = "d56ab1e6-f845-11ec-b939-0242ac120002";
        String TEST_TOKEN = "token1";
        String TEST_RPTID = "77777777777302016723749670035";
        String TEST_DESC = "";
        ZonedDateTime TEST_TIME = ZonedDateTime.now();
        String TEST_EMAIL = "foo@example.com";
        int TEST_AMOUNT = 1;
        TransactionStatusDto TEST_STATUS = TransactionStatusDto.ACTIVATED;

        /**
         * Test
         */
        Transaction transaction = new Transaction(TEST_TRANSACTIONID, TEST_TOKEN, TEST_RPTID, TEST_DESC, TEST_AMOUNT,
                TEST_EMAIL, TEST_STATUS, TEST_TIME);

        Transaction sameTransaction = new Transaction(TEST_TRANSACTIONID, TEST_TOKEN, TEST_RPTID, TEST_DESC,
                TEST_AMOUNT, TEST_EMAIL, TEST_STATUS, TEST_TIME);
        sameTransaction.setCreationDate(transaction.getCreationDate());

        // Different transaction (creation date)
        Transaction differentTransaction = new Transaction("",
                "", "", "", 1, "", null, ZonedDateTime.now());
        differentTransaction.setPaymentToken(TEST_TOKEN);
        differentTransaction.setRptId(TEST_RPTID);
        differentTransaction.setDescription(TEST_DESC);
        differentTransaction.setAmount(TEST_AMOUNT);
        differentTransaction.setStatus(TEST_STATUS);

        /**
         * Assertions
         */
        assertEquals(TEST_TOKEN, transaction.getPaymentToken());
        assertEquals(TEST_RPTID, transaction.getRptId());
        assertEquals(TEST_DESC, transaction.getDescription());
        assertEquals(TEST_AMOUNT, transaction.getAmount());
        assertEquals(TEST_STATUS, transaction.getStatus());

        assertNotEquals(transaction, differentTransaction);
        assertEquals(transaction.hashCode(), sameTransaction.hashCode());
        assertNotEquals(transaction.toString(), differentTransaction.toString());
    }

    @Test
    void shouldConstructTransactionDocumentFromTransaction() {
        TransactionId transactionId = new TransactionId(UUID.fromString("833d303a-f857-11ec-b939-0242ac120002"));
        PaymentToken paymentToken = new PaymentToken("");
        RptId rptId = new RptId("77777777777302016723749670035");
        TransactionDescription description = new TransactionDescription("");
        TransactionAmount amount = new TransactionAmount(100);
        TransactionStatusDto status = TransactionStatusDto.ACTIVATED;
        Email email = new Email("foo@example.com");

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                email,
                status);

        Transaction transactionDocument = Transaction.from(transaction);

        assertEquals(transactionDocument.getPaymentToken(), transaction.getTransactionActivatedData().getPaymentToken());
        assertEquals(transactionDocument.getRptId(), transaction.getRptId().value());
        assertEquals(transactionDocument.getDescription(), transaction.getDescription().value());
        assertEquals(transactionDocument.getAmount(), transaction.getAmount().value());
        assertEquals(ZonedDateTime.parse(transactionDocument.getCreationDate()), transaction.getCreationDate());
        assertEquals(transactionDocument.getStatus(), transaction.getStatus());
    }
}
