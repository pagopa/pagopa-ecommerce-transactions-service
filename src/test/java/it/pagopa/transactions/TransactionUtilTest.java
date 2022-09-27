package it.pagopa.transactions;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.utils.TransactionEventCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionUtilTest {

    @Test
    void transactionUtilitiesTest(){
        assertEquals("TRANSACTION_ACTIVATED_EVENT", TransactionEventCode.TRANSACTION_ACTIVATED_EVENT.toString());
        assertEquals("INITIALIZED", TransactionStatusDto.ACTIVATED.getValue());
    }
}
