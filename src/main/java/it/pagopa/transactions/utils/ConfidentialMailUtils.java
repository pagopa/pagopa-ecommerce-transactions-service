package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.Email;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

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
                .doOnError(e -> log.error("Exception decrypting confidential data", e));
    }

    public Mono<Confidential<Email>> toConfidential(Email clearText) {
        return emailConfidentialDataManager.encrypt(clearText)
                .doOnError(e -> log.error("Exception encrypting confidential data", e));
    }

    public Mono<Confidential<Email>> toConfidential(String email) {
        return toConfidential(new Email(email));
    }
}
