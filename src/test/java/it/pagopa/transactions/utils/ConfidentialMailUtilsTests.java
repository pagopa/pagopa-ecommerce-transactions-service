package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v2.Email;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.EMAIL_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Confidential<Email> computedConfidential = new Confidential<>(emailToken.toString());

        /* preconditions */
        Mockito.when(confidentialDataManager.encrypt(email))
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
