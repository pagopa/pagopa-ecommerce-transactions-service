package it.pagopa.transactions;

import it.pagopa.ecommerce.commons.domain.TransactionEventCode;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionUtilTest {

    @Test
    void transactionUtilitiesTest(){
        assertEquals("TRANSACTION_ACTIVATED_EVENT", TransactionEventCode.TRANSACTION_ACTIVATED_EVENT.toString());
        assertEquals("ACTIVATED", TransactionStatusDto.ACTIVATED.getValue());
    }
}
