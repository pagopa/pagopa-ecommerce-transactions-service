package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.Email;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.transactions.exceptions.ConfidentialDataException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

@Component
@Slf4j
public class ConfidentialMailUtils {

    private final ConfidentialDataManager emailConfidentialDataManager;

    @Autowired
    public ConfidentialMailUtils(ConfidentialDataManager emailConfidentialDataManager) {
        this.emailConfidentialDataManager = emailConfidentialDataManager;
    }

    public Email toEmail(Confidential<Email> encrypted) {
        try {
            return emailConfidentialDataManager.decrypt(encrypted, Email::new);
        } catch (InvalidAlgorithmParameterException | InvalidKeyException | BadPaddingException
                | IllegalBlockSizeException | NoSuchPaddingException | NoSuchAlgorithmException e) {
            log.error("Exception decrypting confidential data", e);
            throw new ConfidentialDataException(e);
        }
    }

    public Confidential<Email> toConfidential(Email clearText) {
        try {
            // TODO change mode with the reversible ones
            return emailConfidentialDataManager.encrypt(ConfidentialDataManager.Mode.AES_GCM_NOPAD, clearText);
        } catch (InvalidKeySpecException | InvalidAlgorithmParameterException | InvalidKeyException
                | BadPaddingException | IllegalBlockSizeException | NoSuchPaddingException
                | NoSuchAlgorithmException e) {
            log.error("Exception encrypting confidential data", e);
            throw new ConfidentialDataException(e);
        }
    }

    public Confidential<Email> toConfidential(String email) {
        return toConfidential(new Email(email));
    }
}
