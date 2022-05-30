package it.pagopa.transactions.data;

import it.pagopa.transactions.domain.RptId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RptIdReadingStringConverterTest {
    @Test
    void shouldConvertStringRepresentationToRptId() {
        String rawRptId = "77777777777302016723749670035";
        RptIdReadingStringConverter converter = new RptIdReadingStringConverter();
        RptId converted = converter.convert(rawRptId);
        RptId expected = new RptId(rawRptId);

        assertNotNull(converted);
        assertEquals(converted, expected);
    }
}
