package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.v2.RptId;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.shaded.org.apache.commons.io.output.TeeOutputStream;

import java.io.ByteArrayOutputStream;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@SpringBootTest
@TestPropertySource(locations = "classpath:application-tests.properties")
class LogFormatterTests {

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
    void testShouldNotMaskValues() {
        // pre-conditions
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);
        RptId rptId = new RptId(TransactionTestUtils.RPT_ID);
        // test
        log.info("TransactionId: [{}]", transactionId.value());
        log.info("RPT ID: [{}]", rptId.value());
        // assertions
        String outcontentString = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(outcontentString.contains("TransactionId: [" + transactionId.value() + "]"));
        assertTrue(outcontentString.contains("RPT ID: [" + rptId.value() + "]"));
        // We expect some additional data in the log string
        assertTrue(outcontentString.contains("@timestamp"));
    }

}
