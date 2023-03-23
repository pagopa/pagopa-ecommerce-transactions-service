package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.PersonalDataVaultMetadata;
import it.pagopa.ecommerce.commons.domain.v1.Email;
import it.pagopa.ecommerce.commons.exceptions.ConfidentialDataException;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.pdv.v1.api.TokenApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;

import java.util.UUID;

import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.EMAIL;
import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.EMAIL_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
public class ConfidentialMailUtilsTests {

    private final ConfidentialDataManager confidentialDataManager = Mockito.mock(ConfidentialDataManager.class);

    private final ConfidentialMailUtils confidentialMailUtils = new ConfidentialMailUtils(
            confidentialDataManager
    );

    @Test
    void shouldEncryptAndDecryptMailSuccessfully() {
        Email email = new Email(EMAIL_STRING);

        UUID emailToken = UUID.randomUUID();
        Confidential<Email> computedConfidential = new Confidential<>(
                new PersonalDataVaultMetadata(),
                emailToken.toString()
        );

        /* preconditions */
        Mockito.when(confidentialDataManager.encrypt(ConfidentialDataManager.Mode.PERSONAL_DATA_VAULT, email))
                .thenReturn(Mono.just(computedConfidential));
        Mockito.when(confidentialDataManager.decrypt(eq(computedConfidential), any()))
                .thenReturn(Mono.just(email));

        /* test */
        Confidential<Email> encrypted = confidentialMailUtils.toConfidential(email).block();
        Email decrypted = confidentialMailUtils.toEmail(encrypted).block();

        /* assert */
        assertEquals(email, decrypted);
    }
}
