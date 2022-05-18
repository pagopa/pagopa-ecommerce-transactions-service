package it.pagopa.transactions;

import it.pagopa.transactions.utils.TransactionEventCode;
import it.pagopa.transactions.utils.TransactionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionUtilTest {

    @Test
    void transactionUtilitiesTest(){
        assertEquals("TRANSACTION_INITIALIZED_EVENT", TransactionEventCode.TRANSACTION_INITIALIZED_EVENT.toString());
        assertEquals("TRANSACTION_INITIALIZED", TransactionStatus.TRANSACTION_INITIALIZED.getCode());
    }
}
