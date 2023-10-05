package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.shaded.org.apache.commons.io.output.TeeOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(outcontentString.contains("cvv: ***"));
        assertTrue(outcontentString.contains("cvv: ****"));
        assertTrue(outcontentString.contains("pan: ****************"));
        assertTrue(outcontentString.contains("pan: **************"));
        assertTrue(outcontentString.contains("*****************"));
        assertTrue(outcontentString.contains("************"));
    }

    @Test
    void shouldMaskCvvPanEmail() throws IOException {
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
        TransactionActivated transactionActivated = TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transactionActivated.getTransactionId(),
                transactionActivated.getPaymentNotices(),
                transactionActivated.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId",
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "XPAY",
                Optional.empty(),
                "VISA",
                cardDetails
        );

        log.info(authorizationData.toString());
        String outcontentString = outContent.toString(StandardCharsets.UTF_8);
        assertFalse(outcontentString.contains("cvv: " + cvv));
        assertFalse(outcontentString.contains(email3ds));
        assertFalse(outcontentString.contains(pan));
        assertFalse(outcontentString.contains("203012"));
        assertTrue(outcontentString.contains("VISA"));
        assertTrue(outcontentString.contains("cvv: ***"));
        assertTrue(outcontentString.contains("pan: ****************"));
    }

}
