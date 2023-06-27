package it.pagopa.transactions.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class EcsEncoderLogMaskerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testPatternMatch() throws IOException {
        LoggerContext loggerContext = new LoggerContext();
        EcsEncoderLogMasker maskedEcsEncoder = new EcsEncoderLogMasker();
        maskedEcsEncoder.addMaskPattern("(abcd)");
        maskedEcsEncoder.setContext(loggerContext);
        maskedEcsEncoder.start();
        LoggingEvent event = new LoggingEvent(
                "test",
                loggerContext.getLogger("ROOT"),
                Level.INFO,
                "test 1234 abcd 1234 test",
                null,
                null
        );
        String maskedMessage = new String(maskedEcsEncoder.encode(event), StandardCharsets.UTF_8);
        maskedEcsEncoder.stop();
        assertEquals("test 1234 **** 1234 test", extractMaskedMessage(maskedMessage));
    }

    @Test
    void testPatternNotMatch() throws IOException {
        LoggerContext loggerContext = new LoggerContext();
        EcsEncoderLogMasker maskedEcsEncoder = new EcsEncoderLogMasker();
        maskedEcsEncoder.addMaskPattern("(abcd)");
        maskedEcsEncoder.setContext(loggerContext);
        maskedEcsEncoder.start();
        LoggingEvent event = new LoggingEvent(
                "test",
                loggerContext.getLogger("ROOT"),
                Level.INFO,
                "test 1234 test",
                null,
                null
        );
        String maskedMessage = new String(maskedEcsEncoder.encode(event), StandardCharsets.UTF_8);
        maskedEcsEncoder.stop();
        assertEquals("test 1234 test", extractMaskedMessage(maskedMessage));
    }

    private String extractMaskedMessage(String maskedMessage) throws IOException {
        return objectMapper.readTree(maskedMessage).get("message").asText();
    }

}
