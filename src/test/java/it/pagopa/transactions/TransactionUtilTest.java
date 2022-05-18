package it.pagopa.transactions;

import it.pagopa.transactions.utils.TransactionEventCode;
import it.pagopa.transactions.utils.TransactionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TransactionUtilTest {

    @Test
    public void transactionUtilitiesTest(){
        assertEquals("TRANSACTION_INITIALIZED_EVENT", TransactionEventCode.TRANSACTION_INITIALIZED_EVENT.toString());
        assertEquals("TRANSACTION_INITIALIZED", TransactionStatus.TRANSACTION_INITIALIZED.getCode());
    }
}
