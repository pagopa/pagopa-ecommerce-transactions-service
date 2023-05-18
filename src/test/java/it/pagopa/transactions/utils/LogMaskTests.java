package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class LogMaskTests {

    private static final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private static final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private static final PrintStream originalOut = System.out;
    private static final PrintStream originalErr = System.err;

    @BeforeAll
    public static void setUpStreams() {
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterAll
    public static void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void testSimpleLog() {
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
    void testIgnoreCase() {
        log.info("cvv=1234");
        log.info("cvV=1234");
        log.info("cVv=1234");
        log.info("cVV=1234");
        log.info("Cvv=1234");
        log.info("CvV=1234");
        log.info("CVv=1234");
        log.info("CVV=1234");
        log.info("pan=4000000000000101");
        log.info("paN=4000000000000101");
        log.info("pAn=4000000000000101");
        log.info("pAN=4000000000000101");
        log.info("Pan=4000000000000101");
        log.info("PaN=4000000000000101");
        log.info("PAn=4000000000000101");
        log.info("PAN=4000000000000101");
        assertEquals(8, StringUtils.countMatches(outContent.toString().toLowerCase(), "cv*****"));
        assertEquals(8, StringUtils.countMatches(outContent.toString().toLowerCase(), "pa******************"));

        log.info("cvv: 123");
        log.info("cvV: 124");
        log.info("cVv: 134");
        log.info("cVV: 234");
        log.info("Cvv: 234");
        log.info("CvV: 134");
        log.info("CVv: 124");
        log.info("CVV: 124");
        log.info("pan: 4000000000000101");
        log.info("paN: 4000000000000101");
        log.info("pAn: 4000000000000101");
        log.info("pAN: 4000000000000101");
        log.info("Pan: 4000000000000101");
        log.info("PaN: 4000000000000101");
        log.info("PAn: 4000000000000101");
        log.info("PAN: 4000000000000101");
        assertEquals(8, StringUtils.countMatches(outContent.toString().toLowerCase(), "cvv:****"));
        assertEquals(8, StringUtils.countMatches(outContent.toString().toLowerCase(), "pan:****************"));
        outContent.reset();
    }

    @Test
    void shouldMaskCvvPanEmail() {
        String cvv = "345";
        String pan = "1658965485269856";
        String email3ds = "g.c@gia.it";
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .cvv(cvv)
                .pan(pan)
                .expiryDate("203012")
                .detailType("card")
                .holderName("John Doe")
                .brand(CardAuthRequestDetailsDto.BrandEnum.VISA)
                .threeDsData(
                        "{\"acctID\":\"ACCT_eac3c21b-78fa-4ae7-a553-84ba9e1945ca\",\"deliveryEmailAddress\":\""
                                + email3ds + "\",\"mobilePhone\":null}"
                );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString()),
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
