package it.pagopa.transactions.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RptIdTest {
    private final String INVALID_RPTID = "";
    private final String VALID_RPTID = "77777777777302016723749670035";

    private final String VALID_FISCAL_CODE = "32009090901";
    private final String VALID_NOTICE_CODE = "302016723749670035";

    String rptIdAsString = VALID_FISCAL_CODE+VALID_NOTICE_CODE;

    @Test
    public void shouldInstiateRptId(){
        RptId rptId = new RptId(VALID_FISCAL_CODE+VALID_NOTICE_CODE);
    }

    @Test
    public void shouldThrowInvalidRptId(){
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            RptId rptId = new RptId(INVALID_RPTID);
        });

        String expectedMessage = "Ill-formed RPT id";
        String actualMessage = exception.getMessage();

        assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    public void shouldReturnRptId(){
        RptId rptId = new RptId(VALID_RPTID);

        assertEquals(rptId.getRptId(), VALID_RPTID);
    }

   @Test
   public void shouldReturnFiscalCode(){
       RptId rptId = new RptId(rptIdAsString);
       assertEquals(VALID_FISCAL_CODE, rptId.getFiscalCode());
   }

    @Test
    public void shouldReturnNoticeCode(){
        RptId rptId = new RptId(rptIdAsString);
        assertEquals(VALID_NOTICE_CODE, rptId.getNoticeId());
    }

    @Test
    public void shouldGetSameRptId(){
        RptId rptId1 = new RptId(rptIdAsString);
        RptId rptId2 = new RptId(rptIdAsString);

        assertTrue(rptId1.equals(rptId2));
    }

}

