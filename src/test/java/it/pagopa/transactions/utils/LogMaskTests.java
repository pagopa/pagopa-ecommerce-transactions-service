package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@ExtendWith(MockitoExtension.class)
public class LogMaskTests {

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @Before
    public void setUpStreams() {
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @After
    public void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    public void testSimpleLog() {
        String simpleMail = "test@test.it";
        log.info(simpleMail);
        String complexmail = "test.test@test.it";
        log.info(complexmail);
        String cvvMsg3 = "cvv: 123";
        log.info(cvvMsg3);
        String cvvMsg4 = "cvv: 1234";
        log.info(cvvMsg4);
        String pan14 = "pan: 12345678901234";
        log.info(pan14);
        String pan16 = "pan: 1234567890123456";
        log.info(pan16);
        assertTrue(outContent.toString().length() > 100);
        assertFalse(outContent.toString().contains(simpleMail));
        assertFalse(outContent.toString().contains(complexmail));
        assertFalse(outContent.toString().contains(cvvMsg3));
        assertFalse(outContent.toString().contains(cvvMsg4));
        assertFalse(outContent.toString().contains(pan14));
        assertFalse(outContent.toString().contains(pan16));
        assertTrue(outContent.toString().contains("cvv:****"));
        assertTrue(outContent.toString().contains("cvv:*****"));
        assertTrue(outContent.toString().contains("pan:*****************"));
        assertTrue(outContent.toString().contains("pan:***************"));
        assertTrue(outContent.toString().contains("*****************"));
        assertTrue(outContent.toString().contains("************"));
    }

    @Test
    public void shouldMaskCvvPanEmail() {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(UUID.randomUUID()),
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                null,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT
        );

        String cvv = "345";
        String pan = "1658965485269856";
        String email3ds = "g.c@gia.it";
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .cvv(cvv)
                .pan(pan)
                .expiryDate("203012")
                .detailType("card")
                .holderName("John Doe")
                .brand("VISA")
                .threeDsData(
                        "{\"acctID\":\"ACCT_eac3c21b-78fa-4ae7-a553-84ba9e1945ca\",\"deliveryEmailAddress\":\""
                                + email3ds + "\",\"mobilePhone\":null}"
                );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                "XPAY",
                cardDetails
        );

        log.info(authorizationData.toString());

        assertTrue(outContent.toString().length() > authorizationData.toString().length());
        assertFalse(outContent.toString().contains(cvv));
        assertFalse(outContent.toString().contains(email3ds));
        assertFalse(outContent.toString().contains(pan));
        assertTrue(outContent.toString().contains("203012"));
        assertTrue(outContent.toString().contains("VISA"));
        assertTrue(outContent.toString().contains("cvv:****"));
        assertTrue(outContent.toString().contains("pan:*****************"));
    }

}
