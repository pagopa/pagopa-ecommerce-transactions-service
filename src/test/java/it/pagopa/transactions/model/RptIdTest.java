package it.pagopa.transactions.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RptIdTest {
    private final String INVALID_RPTID = "";
    private final String VALID_RPTID = "77777777777302016723749670035";

    private final String VALID_FISCAL_CODE = "32009090901";
    private final String VALID_NOTICE_CODE = "302016723749670035";

    String rptIdAsString = VALID_FISCAL_CODE + VALID_NOTICE_CODE;

    @Test
    void shouldInstiateRptId() {
        RptId rptId = new RptId(VALID_FISCAL_CODE + VALID_NOTICE_CODE);
        assertNotNull(rptId);
    }

    @Test
    void shouldThrowInvalidRptId() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            RptId rptId = new RptId(INVALID_RPTID);
        });

        String expectedMessage = "Ill-formed RPT id";
        String actualMessage = exception.getMessage();

        assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    void shouldReturnRptId() {
        RptId rptId = new RptId(VALID_RPTID);

        assertEquals(rptId.getRptId(), VALID_RPTID);
    }

    @Test
    void shouldReturnFiscalCode() {
        RptId rptId = new RptId(rptIdAsString);
        assertEquals(VALID_FISCAL_CODE, rptId.getFiscalCode());
    }

    @Test
    void shouldReturnNoticeCode() {
        RptId rptId = new RptId(rptIdAsString);
        assertEquals(VALID_NOTICE_CODE, rptId.getNoticeId());
    }

    @Test
    void shouldGetSameRptId() {
        RptId rptId1 = new RptId(rptIdAsString);
        RptId rptId2 = new RptId(rptIdAsString);

        assertTrue(rptId1.equals(rptId2));
    }

    @Test
    void shouldGenerateSameHash() {
        RptId rptId1 = new RptId(rptIdAsString);
        RptId rptId2 = new RptId(rptIdAsString);

        assertEquals(rptId1.hashCode(), rptId2.hashCode());
    }
}
