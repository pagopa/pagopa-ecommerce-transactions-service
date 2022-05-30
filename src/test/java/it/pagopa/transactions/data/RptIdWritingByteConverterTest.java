package it.pagopa.transactions.data;

import it.pagopa.transactions.domain.RptId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class RptIdWritingByteConverterTest {
    @Test
    void shouldConvertRptIdToValueByteRepresentation() {
        String rawRptId = "77777777777302016723749670035";
        RptId rptId = new RptId(rawRptId);
        RptIdWritingByteConverter converter = new RptIdWritingByteConverter();
        byte[] converted = converter.convert(rptId);
        byte[] expected = rawRptId.getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(converted, expected);
    }
}
