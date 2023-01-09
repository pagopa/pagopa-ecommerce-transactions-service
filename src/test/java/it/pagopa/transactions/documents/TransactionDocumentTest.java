package it.pagopa.transactions.documents;

import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.Transaction;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        Transaction transaction = new Transaction(
                TEST_TRANSACTIONID,
                TEST_TOKEN,
                TEST_RPTID,
                TEST_DESC,
                TEST_AMOUNT,
                TEST_EMAIL,
                TEST_STATUS,
                TEST_TIME
        );

        Transaction sameTransaction = new Transaction(
                TEST_TRANSACTIONID,
                TEST_TOKEN,
                TEST_RPTID,
                TEST_DESC,
                TEST_AMOUNT,
                TEST_EMAIL,
                TEST_STATUS,
                TEST_TIME
        );
        sameTransaction.setCreationDate(transaction.getCreationDate());

        // Different transaction (creation date)
        Transaction differentTransaction = new Transaction(
                "",
                "",
                "",
                "",
                1,
                "",
                null,
                ZonedDateTime.now()
        );
        it.pagopa.ecommerce.commons.documents.PaymentNotice paymentNotice = new PaymentNotice(
                TEST_TOKEN,
                TEST_RPTID,
                TEST_DESC,
                TEST_AMOUNT,
                null
        );
        differentTransaction.setPaymentNotices(Arrays.asList(paymentNotice));
        differentTransaction.setStatus(TEST_STATUS);

        /**
         * Assertions
         */
        assertEquals(TEST_TOKEN, transaction.getPaymentNotices().get(0).getPaymentToken());
        assertEquals(TEST_RPTID, transaction.getPaymentNotices().get(0).getRptId());
        assertEquals(TEST_DESC, transaction.getPaymentNotices().get(0).getDescription());
        assertEquals(TEST_AMOUNT, transaction.getPaymentNotices().get(0).getAmount());
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
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                Arrays.asList(
                        new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode
                        )
                ),
                email,
                faultCode,
                faultCodeString,
                status,
                Transaction.ClientId.UNKNOWN
        );

        Transaction transactionDocument = Transaction.from(transaction);

        assertEquals(
                transactionDocument.getPaymentNotices().get(0).getPaymentToken(),
                transaction.getTransactionActivatedData().getPaymentNotices().get(0).getPaymentToken()
        );
        assertEquals(
                transactionDocument.getPaymentNotices().get(0).getRptId(),
                transaction.getPaymentNotices().get(0).rptId().value()
        );
        assertEquals(
                transactionDocument.getPaymentNotices().get(0).getDescription(),
                transaction.getPaymentNotices().get(0).transactionDescription().value()
        );
        assertEquals(
                transactionDocument.getPaymentNotices().get(0).getAmount(),
                transaction.getPaymentNotices().get(0).transactionAmount().value()
        );
        assertEquals(ZonedDateTime.parse(transactionDocument.getCreationDate()), transaction.getCreationDate());
        assertEquals(transactionDocument.getStatus(), transaction.getStatus());
    }
}
