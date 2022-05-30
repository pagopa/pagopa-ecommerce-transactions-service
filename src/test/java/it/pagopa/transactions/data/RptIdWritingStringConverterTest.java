package it.pagopa.transactions.data;

import it.pagopa.transactions.domain.RptId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RptIdWritingStringConverterTest {
    @Test
    void shouldConvertRptIdToStringRepresentation() {
        String rawRptId = "77777777777302016723749670035";
        RptId rptId = new RptId(rawRptId);
        RptIdWritingStringConverter converter = new RptIdWritingStringConverter();
        String converted = converter.convert(rptId);

        assertEquals(converted, rawRptId);
    }
}
