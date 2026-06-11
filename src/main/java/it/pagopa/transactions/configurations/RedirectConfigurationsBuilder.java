package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.utils.RedirectUrlMappingConf;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class used to read all the PSP configurations that will be used
 * during redirect transaction
 */
@Configuration
@Slf4j
public class RedirectConfigurationsBuilder {
    /**
     * Create a {@link RedirectUrlMappingConf} that will handle, to every handled
     * PSP, the backend URI to be used to perform Redirect payment flow api call
     *
     * @param expectedMatchingCriteria - set of all redirect payment type codes to
     *                                 be handled flow
     * @param pspUrlMapping            - configuration parameter that contains PSP
     *                                 to URI mapping
     * @return an {@link it.pagopa.ecommerce.commons.utils.RedirectUrlMappingConf}
     *         instance that handle a configuration map for every PSPs
     */
    @Bean
    public RedirectUrlMappingConf redirectUrlMappingConf(
                                                         @Value(
                                                             "${redirect.expectedMatchingCriteria}"
                                                         ) String expectedMatchingCriteria,
                                                         @Value(
                                                             "${redirect.pspUrlMapping}"
                                                         ) String pspUrlMapping
    ) {
        return new RedirectUrlMappingConf(pspUrlMapping, expectedMatchingCriteria);
    }

}
