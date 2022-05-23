package it.pagopa.transactions.model;

import it.pagopa.transactions.annotations.ValueObject;

import java.util.regex.Pattern;

@ValueObject
public record RptId(String value) {
    private static final Pattern rptIdRegex = Pattern.compile("([a-zA-Z\\d]{1,35})|(RF\\d{2}[a-zA-Z\\d]{1,21})");

    public RptId {
        if (!rptIdRegex.matcher(value).matches()) {
            throw new IllegalArgumentException("Ill-formed RPT id: " + value + ". Doesn't match format: " + rptIdRegex.pattern());
        }

    }

    public String getFiscalCode() {
        return value.substring(0, 11);
    }

    public String getNoticeId() {
        return value.substring(11);
    }
}
