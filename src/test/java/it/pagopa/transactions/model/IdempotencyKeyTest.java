package it.pagopa.transactions.model;


import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

public class IdempotencyKeyTest {
    private final String VALID_FISCAL_CODE = "32009090901";
    private final String INVALID_FISCAL_CODE = "3200909090";

    private final String VALID_KEY_ID = "aabbccddee";
    private final String INVALID_KEY_ID = "aabbccddeeffgg";


    @Test
    public void shouldThrowInvalidFiscalCode(){
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            IdempotencyKey key = new IdempotencyKey(INVALID_FISCAL_CODE, VALID_KEY_ID);
        });

        String expectedMessage = "PSP fiscal code doesn't match regex";
        String actualMessage = exception.getMessage();

        assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    public void shouldThrowInvalidKeyId(){
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            IdempotencyKey key = new IdempotencyKey(VALID_FISCAL_CODE, INVALID_KEY_ID);
        });

        String expectedMessage = "Key identifier doesn't match regex";
        String actualMessage = exception.getMessage();

        assertTrue(actualMessage.contains(expectedMessage));
    }


    @Test
    public void shouldReturnKey() {
        IdempotencyKey key = new IdempotencyKey(VALID_FISCAL_CODE, VALID_KEY_ID);

        assertTrue(key.getKey().equalsIgnoreCase(VALID_FISCAL_CODE + "_" + VALID_KEY_ID));
    }

    @Test
    public void shouldGenerateSameKey() {
        IdempotencyKey key1 = new IdempotencyKey(VALID_FISCAL_CODE, VALID_KEY_ID);
        IdempotencyKey key2 = new IdempotencyKey(VALID_FISCAL_CODE, VALID_KEY_ID);

        assertTrue(key1.equals(key2));
    }

    @Test
    public void shouldReturnHashcode() {
        IdempotencyKey key = new IdempotencyKey(VALID_FISCAL_CODE, VALID_KEY_ID);
        assertEquals(key.hashCode(), Objects.hash(VALID_FISCAL_CODE + "_" + VALID_KEY_ID));
    }
}
