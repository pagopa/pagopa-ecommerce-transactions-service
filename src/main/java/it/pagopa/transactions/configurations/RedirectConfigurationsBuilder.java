package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.exceptions.RedirectConfigurationException;
import it.pagopa.ecommerce.commons.exceptions.RedirectConfigurationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
    public Map<String, URI> redirectBeApiCallUriMap(
                                                    @Value(
                                                        "${redirect.paymentTypeCodeList}"
                                                    ) Set<String> paymentTypeCodeList,
                                                    @Value(
                                                        "#{${redirect.pspUrlMapping}}"
                                                    ) Map<String, String> pspUrlMapping
    ) {
        Map<String, URI> redirectUriMap = new HashMap<>();
        // URI.create throws IllegalArgumentException that will prevent module load for
        // invalid PSP URI configuration
        pspUrlMapping.forEach(
                (
                 pspId,
                 uri
                ) -> redirectUriMap.put(pspId, URI.create(uri))
        );
        Set<String> missingKeys = paymentTypeCodeList
                .stream()
                .filter(Predicate.not(redirectUriMap::containsKey))
                .collect(Collectors.toSet());
        if (!missingKeys.isEmpty()) {
            throw new RedirectConfigurationException(
                    "Misconfigured redirect.pspUrlMapping, the following redirect payment type code b.e. URIs are not configured: %s"
                            .formatted(missingKeys),
                    RedirectConfigurationType.BACKEND_URLS
            );
        }
        return redirectUriMap;

    }

}
