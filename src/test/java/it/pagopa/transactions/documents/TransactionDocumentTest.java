
package it.pagopa.transactions.documents;


import it.pagopa.transactions.utils.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TransactionDocumentTest {


    @Test
    void shouldGetAndSetTransaction(){
        String TEST_TOKEN = "token1";
        String TEST_RPTID = "77777777777302016723749670035";
        String TEST_DESC = "";
        int TEST_AMOUNT = 1;
        TransactionStatus TEST_STATUS = TransactionStatus.TRANSACTION_INITIALIZED;

        /**
         * Test
         */
        Transaction transaction = new Transaction(TEST_TOKEN, TEST_RPTID, TEST_DESC, TEST_AMOUNT, TEST_STATUS);

        Transaction sameTransaction = new Transaction(TEST_TOKEN, TEST_RPTID, TEST_DESC, TEST_AMOUNT, TEST_STATUS);
        sameTransaction.setCreationDate(transaction.getCreationDate());

        // Different transaction (creation date)
        Transaction differentTransaction = new Transaction(
                "", "", "", 1, null);
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

        assertFalse(transaction.equals(differentTransaction));
        assertEquals(transaction.hashCode(), sameTransaction.hashCode());
        assertFalse(transaction.toString().equals(differentTransaction.toString()));
    }   
}
