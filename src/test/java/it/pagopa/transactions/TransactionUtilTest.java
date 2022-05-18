package it.pagopa.transactions;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.utils.TransactionEventCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionUtilTest {

    @Test
    void transactionUtilitiesTest(){
        assertEquals("TRANSACTION_INITIALIZED_EVENT", TransactionEventCode.TRANSACTION_INITIALIZED_EVENT.toString());
        assertEquals("INITIALIZED", TransactionStatusDto.INITIALIZED.getValue());
    }
}
