package it.pagopa.transactions;

import it.pagopa.ecommerce.commons.utils.ConfidentialDataManagerTest;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TestConfiguration {
    @Bean
    ConfidentialMailUtils confidentialMailUtils() {
        return new ConfidentialMailUtils(ConfidentialDataManagerTest.getMock());
    }
}
