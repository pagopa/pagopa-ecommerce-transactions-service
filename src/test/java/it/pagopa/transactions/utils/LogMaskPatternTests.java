package it.pagopa.transactions.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogMaskPatternTests {

    @Test
    void testPatternMatch() {
        LoggerContext loggerContext = new LoggerContext();
        LogMaskerPatternLayout layout = new LogMaskerPatternLayout();
        layout.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n");
        layout.addMaskPattern("(abcd)");
        layout.setContext(loggerContext);
        layout.start();
        LoggingEvent event = new LoggingEvent(
                "test",
                loggerContext.getLogger("ROOT"),
                Level.INFO,
                "test 1234 abcd 1234 test",
                null,
                null
        );
        String maskedMessage = layout.doLayout(event);
        layout.stop();
        assertEquals("test 1234 **** 1234 test", extractMaskedMessage(maskedMessage));
    }

    @Test
    void testPatternNotMatch() {
        LoggerContext loggerContext = new LoggerContext();
        LogMaskerPatternLayout layout = new LogMaskerPatternLayout();
        layout.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        layout.addMaskPattern("(abcd)");
        layout.setContext(loggerContext);
        layout.start();
        LoggingEvent event = new LoggingEvent(
                "test",
                loggerContext.getLogger("ROOT"),
                Level.INFO,
                "test 1234 test",
                null,
                null
        );
        String maskedMessage = layout.doLayout(event);
        layout.stop();
        assertEquals("test 1234 test", extractMaskedMessage(maskedMessage));
    }

    private String extractMaskedMessage(String maskedMessage) {
        return maskedMessage.split(" - ")[1].trim();
    }

}
