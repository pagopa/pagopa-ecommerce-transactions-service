package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.Email;
import it.pagopa.ecommerce.commons.exceptions.ConfidentialDataException;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.pdv.v1.api.TokenApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
public class ConfidentialMailUtilsTests {

    private final ConfidentialDataManager confidentialDataManager = TransactionTestUtils.confidentialDataManager;

    private final ConfidentialMailUtils confidentialMailUtils = new ConfidentialMailUtils(
            confidentialDataManager
    );

    private final TokenApi pdvApiClient = new TokenApi();

    private final String EMAIL = "test@test.it";

    @Test
    void shouldEncryptAndDecryptMailSuccessfully() {
        Email email = new Email(EMAIL);
        Confidential<Email> encrypted = confidentialMailUtils.toConfidential(email).block();
        Email decrypted = confidentialMailUtils.toEmail(encrypted).block();
        assertEquals(email, decrypted);
    }

    @Test
    void shouldFailEncryptionForInvalidConfiguredKey() {
        ConfidentialDataManager misconfiguredKeyConfidentialDataManager = new ConfidentialDataManager(
                new SecretKeySpec(new byte[1], "AES"),
                pdvApiClient
        );
        ConfidentialMailUtils misconfiguredConfidentialMailUtils = new ConfidentialMailUtils(
                misconfiguredKeyConfidentialDataManager
        );
        assertThrows(
                ConfidentialDataException.class,
                () -> misconfiguredConfidentialMailUtils.toConfidential(EMAIL).block()
        );

    }

    @Test
    void shouldFailDecryptionForInvalidConfiguredKey() {
        Confidential<Email> encrypted = confidentialMailUtils.toConfidential(EMAIL).block();
        ConfidentialDataManager misconfiguredKeyConfidentialDataManager = new ConfidentialDataManager(
                new SecretKeySpec(new byte[1], "AES"),
                pdvApiClient
        );
        ConfidentialMailUtils misconfiguredConfidentialMailUtils = new ConfidentialMailUtils(
                misconfiguredKeyConfidentialDataManager
        );
        assertThrows(
                ConfidentialDataException.class,
                () -> misconfiguredConfidentialMailUtils.toEmail(encrypted).block()
        );

    }
}
