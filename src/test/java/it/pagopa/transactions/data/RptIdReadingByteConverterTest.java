package it.pagopa.transactions.data;

import it.pagopa.transactions.domain.RptId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RptIdReadingByteConverterTest {
    @Test
    void shouldConvertByteRepresentationToRptId() {
        String rawRptId = "77777777777302016723749670035";
        RptIdReadingByteConverter converter = new RptIdReadingByteConverter();
        RptId converted = converter.convert(rawRptId.getBytes(StandardCharsets.UTF_8));
        RptId expected = new RptId(rawRptId);

        assertNotNull(converted);
        assertEquals(converted, expected);
    }
}
