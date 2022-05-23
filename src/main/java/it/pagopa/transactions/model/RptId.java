package it.pagopa.transactions.model;

import it.pagopa.transactions.annotations.ValueObject;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.util.regex.Pattern;

@ValueObject
public record RptId(String rptId) {
    private static final Pattern rptIdRegex = Pattern.compile("([a-zA-Z\\d]{1,35})|(RF\\d{2}[a-zA-Z\\d]{1,21})");

    public RptId {
        if (!rptIdRegex.matcher(rptId).matches()) {
            throw new IllegalArgumentException("Ill-formed RPT id: " + rptId + ". Doesn't match format: " + rptIdRegex.pattern());
        }

    }

    public String getFiscalCode() {
        return rptId.substring(0, 11);
    }

    public String getNoticeId() {
        return rptId.substring(11);
    }
}
