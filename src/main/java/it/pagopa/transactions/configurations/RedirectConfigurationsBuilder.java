package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.utils.RedirectKeysConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

/**
 * Configuration class used to read all the PSP configurations that will be used
 * during redirect transaction
 */
@Configuration
@Slf4j
public class RedirectConfigurationsBuilder {
    /**
     * Create a Map &lt String,URI &gt that will associate, to every handled PSP,
     * the backend URI to be used to perform Redirect payment flow api call
     *
     * @param paymentTypeCodeList - set of all redirect payment type codes to be
     *                            handled flow
     * @param pspUrlMapping       - configuration parameter that contains PSP to URI
     *                            mapping
     * @return a configuration map for every PSPs
     */
    @Bean
    public RedirectKeysConfiguration redirectKeysConfig(
                                                        @Value(
                                                            "${redirect.paymentTypeCodeList}"
                                                        ) Set<String> paymentTypeCodeList,
                                                        @Value(
                                                            "#{${redirect.pspUrlMapping}}"
                                                        ) Map<String, String> pspUrlMapping
    ) {
        return new RedirectKeysConfiguration(pspUrlMapping, paymentTypeCodeList);
    }

}
