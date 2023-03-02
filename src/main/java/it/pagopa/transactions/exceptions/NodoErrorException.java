package it.pagopa.transactions.exceptions;

import it.pagopa.generated.transactions.model.CtFaultBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ResponseStatus(value = HttpStatus.BAD_GATEWAY)
public class NodoErrorException extends RuntimeException {
    private static final Pattern faultCodePattern = Pattern.compile("(PAA|PPT)_\\S+");

    private final String faultCode;

    public NodoErrorException(CtFaultBean faultBean) {

        this.faultCode = getFaultCodeFromBean(faultBean);
    }

    public String getFaultCode() {
        return faultCode;
    }

    private static String getFaultCodeFromBean(CtFaultBean faultBean) {
        String description = faultBean.getDescription();

        if (description != null) {
            Matcher matcher = faultCodePattern.matcher(description);

            if (matcher.find()) {
                return matcher.group();
            }
        }

        return faultBean.getFaultCode();
    }
}
