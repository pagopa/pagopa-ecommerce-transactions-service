package it.pagopa.transactions.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ContextBase;
import org.junit.Test;
import org.mockito.Mock;

import static org.junit.Assert.assertEquals;

public class LogMaskPatternTests {

    @Test
    public void testPattern() {
        LogMaskerPatternLayout layout = new LogMaskerPatternLayout();
        layout.setPattern("test 1234 abcd 1234 test");
        layout.addMaskPattern("(abcd)");
        layout.setContext(new LoggerContext());
        layout.start();
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setMessage("Message test");
        String maskedMessage = layout.doLayout(event);
        layout.stop();
        assertEquals("test 1234 **** 1234 test", maskedMessage);
    }

}
