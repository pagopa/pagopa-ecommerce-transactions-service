package it.pagopa.transactions.documents;

import it.pagopa.ecommerce.commons.documents.v1.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
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
        Confidential<Email> CONFIDENTIAL_TEST_EMAIL = TransactionTestUtils.EMAIL;
        int TEST_AMOUNT = 1;
        TransactionStatusDto TEST_STATUS = TransactionStatusDto.ACTIVATED;

        /*
         * Test
         */
        /*
         * String transactionId,
         * java.util.List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice>
         * paymentNotices,
         *
         * @Nullable Integer feeTotal, String email,
         * it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
         * status, Transaction.ClientId clientId, String creationDate
         */
        Transaction transaction = new Transaction(
                TEST_TRANSACTIONID,
                List.of(
                        new PaymentNotice(
                                TEST_TOKEN,
                                TEST_RPTID,
                                TEST_DESC,
                                TEST_AMOUNT,
                                ""
                        )
                ),
                0,
                CONFIDENTIAL_TEST_EMAIL,
                TEST_STATUS,
                Transaction.ClientId.CHECKOUT,
                TEST_TIME.toString()
        );

        Transaction sameTransaction = new Transaction(
                TEST_TRANSACTIONID,
                List.of(
                        new PaymentNotice(
                                TEST_TOKEN,
                                TEST_RPTID,
                                TEST_DESC,
                                TEST_AMOUNT,
                                ""
                        )
                ),
                0,
                CONFIDENTIAL_TEST_EMAIL,
                TEST_STATUS,
                Transaction.ClientId.CHECKOUT,
                TEST_TIME.toString()
        );

        // Different transaction (creation date)
        Transaction differentTransaction = new Transaction(
                TEST_TRANSACTIONID,
                List.of(
                        new PaymentNotice(
                                TEST_TOKEN,
                                TEST_RPTID,
                                TEST_DESC,
                                TEST_AMOUNT,
                                ""
                        )
                ),
                0,
                CONFIDENTIAL_TEST_EMAIL,
                TEST_STATUS,
                Transaction.ClientId.CHECKOUT,
                ZonedDateTime.now().toString()
        );
        it.pagopa.ecommerce.commons.documents.v1.PaymentNotice paymentNotice = new PaymentNotice(
                TEST_TOKEN,
                TEST_RPTID,
                TEST_DESC,
                TEST_AMOUNT,
                null
        );
        differentTransaction.setPaymentNotices(List.of(paymentNotice));
        differentTransaction.setStatus(TEST_STATUS);

        /*
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
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new it.pagopa.ecommerce.commons.domain.v1.PaymentNotice(
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
                Transaction.ClientId.CHECKOUT
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
