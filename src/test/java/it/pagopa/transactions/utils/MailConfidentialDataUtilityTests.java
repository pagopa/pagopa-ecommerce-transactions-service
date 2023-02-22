package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.Email;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.transactions.exceptions.ConfidentialDataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
public class MailConfidentialDataUtilityTests {

    private ConfidentialDataManager confidentialDataManager = TransactionTestUtils.confidentialDataManager;

    private MailConfidentialDataUtility mailConfidentialDataUtility = new MailConfidentialDataUtility(
            confidentialDataManager
    );

    private final String EMAIL = "test@test.it";

    @Test
    void shouldEncryptAndDecryptMailSuccessfully() {
        Email email = new Email(EMAIL);
        Confidential<Email> encrypted = mailConfidentialDataUtility.toConfidential(email);
        Email decrypted = mailConfidentialDataUtility.toEmail(encrypted);
        assertEquals(email, decrypted);
    }

    @Test
    void shouldFailEncryptionForInvalidConfiguredKey() {
        ConfidentialDataManager misconfiguredKeyConfidentialDataManager = new ConfidentialDataManager(
                new SecretKeySpec(new byte[1], "AES")
        );
        MailConfidentialDataUtility misconfiguredMailConfidentialDataUtility = new MailConfidentialDataUtility(
                misconfiguredKeyConfidentialDataManager
        );
        assertThrows(
                ConfidentialDataException.class,
                () -> misconfiguredMailConfidentialDataUtility.toConfidential(EMAIL)
        );

    }

    @Test
    void shouldFailDecryptionForInvalidConfiguredKey() {
        Confidential<Email> encrypted = mailConfidentialDataUtility.toConfidential(EMAIL);
        ConfidentialDataManager misconfiguredKeyConfidentialDataManager = new ConfidentialDataManager(
                new SecretKeySpec(new byte[1], "AES")
        );
        MailConfidentialDataUtility misconfiguredMailConfidentialDataUtility = new MailConfidentialDataUtility(
                misconfiguredKeyConfidentialDataManager
        );
        assertThrows(
                ConfidentialDataException.class,
                () -> misconfiguredMailConfidentialDataUtility.toEmail(encrypted)
        );

    }
}
