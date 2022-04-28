package it.pagopa.transactions.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.redis.core.RedisHash;

import java.util.Objects;
import java.util.regex.Pattern;

@RedisHash(value = "keys")
public final class IdempotencyKey {
    private static final Pattern pspFiscalCodeRegex = Pattern.compile("\\d{11}");
    private static final Pattern keyIdentifierRegex = Pattern.compile("[a-zA-Z\\d]{10}");

    @Id
    private final RptId id;
    private final String key;

    @PersistenceConstructor
    private IdempotencyKey(RptId id, String key) {
        this.id = id;
        this.key = key;
    }

    public IdempotencyKey(RptId id, String pspFiscalCode, String keyIdentifier) {
        if (!pspFiscalCodeRegex.matcher(pspFiscalCode).matches()) {
            throw new IllegalArgumentException("PSP fiscal code doesn't match regex: " + pspFiscalCodeRegex.pattern());
        }

        if (!keyIdentifierRegex.matcher(keyIdentifier).matches()) {
            throw new IllegalArgumentException("Key identifier doesn't match regex: " + keyIdentifierRegex.pattern());
        }

        this.id = id;
        this.key = pspFiscalCode + "_" + keyIdentifier;
    }

    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (IdempotencyKey) obj;
        return Objects.equals(this.key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }

    @Override
    public String toString() {
        return "IdempotencyKey[" +
                "value=" + key + ']';
    }

}
