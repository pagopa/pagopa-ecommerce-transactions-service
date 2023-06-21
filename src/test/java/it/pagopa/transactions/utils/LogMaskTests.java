package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.shaded.org.apache.commons.io.output.TeeOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
@TestPropertySource(locations = "classpath:application-tests.properties")
class LogMaskTests {

    private static final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private static final ByteArrayOutputStream errContent = new ByteArrayOutputStream();

    @BeforeAll
    public static void setUpStreams() {
        System.setOut(new PrintStream(new TeeOutputStream(System.out, outContent)));
        System.setErr(new PrintStream(new TeeOutputStream(System.out, errContent)));
    }

    @AfterAll
    public static void restoreStreams() {
        System.setOut(System.out);
        System.setErr(System.err);
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
        String outcontentString = outContent.toString(StandardCharsets.UTF_8);
        assertFalse(outcontentString.contains(simpleMail));
        assertFalse(outcontentString.contains(complexmail));
        assertFalse(outcontentString.contains(cvvMsg3));
        assertFalse(outcontentString.contains(cvvMsg4));
        assertFalse(outcontentString.contains(pan14));
        assertFalse(outcontentString.contains(pan16));
        assertTrue(outcontentString.contains("cvv:****"));
        assertTrue(outcontentString.contains("cvv:*****"));
        assertTrue(outcontentString.contains("pan:*****************"));
        assertTrue(outcontentString.contains("pan:***************"));
        assertTrue(outcontentString.contains("*****************"));
        assertTrue(outcontentString.contains("************"));
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
        String outcontentString = outContent.toString(StandardCharsets.UTF_8);
        assertEquals(8, StringUtils.countMatches(outcontentString.toLowerCase(), "cv*****"));
        assertEquals(8, StringUtils.countMatches(outcontentString.toLowerCase(), "pa******************"));
        outContent.reset();
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
        outcontentString = outContent.toString(StandardCharsets.UTF_8);
        assertEquals(8, StringUtils.countMatches(outcontentString.toLowerCase(), "cvv:****"));
        assertEquals(8, StringUtils.countMatches(outcontentString.toLowerCase(), "pan:****************"));
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
        String outcontentString = outContent.toString(StandardCharsets.UTF_8);
        assertFalse(outcontentString.contains(cvv));
        assertFalse(outcontentString.contains(email3ds));
        assertFalse(outcontentString.contains(pan));
        assertTrue(outcontentString.contains("203012"));
        assertTrue(outcontentString.contains("VISA"));
        assertTrue(outcontentString.contains("cvv:****"));
        assertTrue(outcontentString.contains("pan:*****************"));
    }

}
