package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.Email;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Component
@Slf4j
public class ConfidentialMailUtils {

    private final ConfidentialDataManager emailConfidentialDataManager;

    @Autowired
    public ConfidentialMailUtils(ConfidentialDataManager emailConfidentialDataManager) {
        this.emailConfidentialDataManager = emailConfidentialDataManager;
    }

    public Mono<Email> toEmail(Confidential<Email> encrypted) {
        return emailConfidentialDataManager.decrypt(encrypted, Email::new)
                .doOnError(e -> log.error("Exception encrypting confidential data", e));
    }

    public Mono<Confidential<Email>> toConfidential(Email clearText) {
        return emailConfidentialDataManager.encrypt(ConfidentialDataManager.Mode.PERSONAL_DATA_VAULT, clearText)
                .doOnError(e -> log.error("Exception encrypting confidential data", e));
    }

    public Mono<Confidential<Email>> toConfidential(String email) {
        return toConfidential(new Email(email));
    }
}
