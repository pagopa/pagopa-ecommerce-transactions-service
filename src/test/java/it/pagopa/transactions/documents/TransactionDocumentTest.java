
package it.pagopa.transactions.documents;


import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TransactionDocumentTest {


    @Test
    void shouldGetAndSetTransaction(){
        String TEST_ID = "id";
        String TEST_TOKEN = "token1";
        String TEST_RPTID = "77777777777302016723749670035";
        String TEST_DESC = "";
        ZonedDateTime TEST_TIME = ZonedDateTime.now();
        int TEST_AMOUNT = 1;
        TransactionStatusDto TEST_STATUS = TransactionStatusDto.INITIALIZED;

        /**
         * Test
         */
        Transaction transaction = new Transaction(TEST_ID, TEST_TOKEN, TEST_RPTID, TEST_DESC, TEST_AMOUNT, TEST_STATUS, TEST_TIME);

        Transaction sameTransaction = new Transaction(TEST_ID, TEST_TOKEN, TEST_RPTID, TEST_DESC, TEST_AMOUNT, TEST_STATUS, TEST_TIME);
        sameTransaction.setCreationDate(transaction.getCreationDate());

        // Different transaction (creation date)
        Transaction differentTransaction = new Transaction(
                "", "", "", "", 1, null, ZonedDateTime.now());
        differentTransaction.setTransactionId(TEST_ID);
        differentTransaction.setPaymentToken(TEST_TOKEN);
        differentTransaction.setRptId(TEST_RPTID);
        differentTransaction.setDescription(TEST_DESC);
        differentTransaction.setAmount(TEST_AMOUNT);
        differentTransaction.setStatus(TEST_STATUS);

        /**
         * Assertions
         */
        assertEquals(TEST_ID, transaction.getTransactionId());
        assertEquals(TEST_TOKEN, transaction.getPaymentToken());
        assertEquals(TEST_RPTID, transaction.getRptId());
        assertEquals(TEST_DESC, transaction.getDescription());
        assertEquals(TEST_AMOUNT, transaction.getAmount());
        assertEquals(TEST_STATUS, transaction.getStatus());

        assertNotEquals(transaction, differentTransaction);
        assertEquals(transaction.hashCode(), sameTransaction.hashCode());
        assertNotEquals(transaction.toString(), differentTransaction.toString());
    }   
}
