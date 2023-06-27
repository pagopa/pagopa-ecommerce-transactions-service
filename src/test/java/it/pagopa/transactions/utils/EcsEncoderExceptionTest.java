package it.pagopa.transactions.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import co.elastic.logging.logback.EcsEncoder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class EcsEncoderExceptionTest {

    @Mock
    ObjectMapper objectMapperMock;

    @Test
    void testPatternMatchReadException() throws IOException {
        Mockito.when(objectMapperMock.readTree(anyString())).thenThrow(JsonProcessingException.class);
        EcsEncoderLogMasker maskedEcsEncoder = new EcsEncoderLogMasker(objectMapperMock);
        LoggerContext loggerContext = new LoggerContext();

        maskedEcsEncoder.addMaskPattern("([\\d+]{3,20})");
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
        assertEquals("test **** abcd **** test", extractMaskedMessage(maskedMessage));
        assertEquals(15, StringUtils.countOccurrencesOf(maskedMessage, "*"));
    }

    @Test
    void testPatternMatchWriteException() throws IOException {
        EcsEncoder ecsEncoder = new EcsEncoder();
        String message = "test 1234 abcd 1234 test";
        LoggerContext loggerContext = new LoggerContext();
        LoggingEvent event = new LoggingEvent(
                "test",
                loggerContext.getLogger("ROOT"),
                Level.INFO,
                "test 1234 abcd 1234 test",
                null,
                null
        );
        byte[] encodedLog = ecsEncoder.encode(event);
        String clearLog = new String(encodedLog, StandardCharsets.UTF_8);
        Mockito.when(objectMapperMock.readTree(anyString())).thenReturn(new ObjectMapper().readTree(clearLog));
        Mockito.when(objectMapperMock.writeValueAsString(any())).thenThrow(JsonProcessingException.class);
        EcsEncoderLogMasker maskedEcsEncoder = new EcsEncoderLogMasker(objectMapperMock);
        maskedEcsEncoder.addMaskPattern("([\\d+]{3,20})");
        maskedEcsEncoder.setContext(loggerContext);
        maskedEcsEncoder.start();

        String maskedMessage = new String(maskedEcsEncoder.encode(event), StandardCharsets.UTF_8);
        maskedEcsEncoder.stop();
        System.out.println(maskedMessage);
        assertEquals("test **** abcd **** test", extractMaskedMessage(maskedMessage));
        assertEquals(15, StringUtils.countOccurrencesOf(maskedMessage, "*"));
    }

    private String extractMaskedMessage(String maskedMessage) throws IOException {
        return new ObjectMapper().readTree(maskedMessage).get("message").asText();
    }

}
