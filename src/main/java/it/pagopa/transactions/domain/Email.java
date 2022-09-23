package it.pagopa.transactions.domain;

import java.util.regex.Pattern;

public record Email(String value) {
    private static final Pattern emailRegex = Pattern.compile(".*@.*\\..*");

    public Email {
        if (!emailRegex.matcher(value).matches()) {
            throw new IllegalArgumentException("Ill-formed email: " + value + ". Doesn't match format: " + emailRegex.pattern());
        }

    }

}
