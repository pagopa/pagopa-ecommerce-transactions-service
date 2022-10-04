package it.pagopa.transactions.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class EmailTest {
    private static final String INVALID_EMAIL = "";
    private static final String VALID_EMAIL = "foo@example.com";

    @Test
    void shouldConstructValidEmail() {
        assertDoesNotThrow(() -> new Email(VALID_EMAIL));
    }

    @Test
    void shouldThrowOnInvalidEmail() {
        assertThrows(IllegalArgumentException.class, () -> new Email(INVALID_EMAIL));
    }
}
