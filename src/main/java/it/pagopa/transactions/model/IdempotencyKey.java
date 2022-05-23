package it.pagopa.transactions.model;

import it.pagopa.transactions.annotations.ValueObject;
import lombok.Value;

import java.util.regex.Pattern;

@ValueObject
@Value
public class IdempotencyKey {
    private static final Pattern pspFiscalCodeRegex = Pattern.compile("\\d{11}");
    private static final Pattern keyIdentifierRegex = Pattern.compile("[a-zA-Z\\d]{10}");
    String key;

    public IdempotencyKey(String pspFiscalCode, String keyIdentifier) {
        if (!pspFiscalCodeRegex.matcher(pspFiscalCode).matches()) {
            throw new IllegalArgumentException("PSP fiscal code doesn't match regex: " + pspFiscalCodeRegex.pattern());
        }

        if (!keyIdentifierRegex.matcher(keyIdentifier).matches()) {
            throw new IllegalArgumentException("Key identifier doesn't match regex: " + keyIdentifierRegex.pattern());
        }

        this.key = pspFiscalCode + "_" + keyIdentifier;
    }
}
