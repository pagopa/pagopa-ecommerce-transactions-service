package it.pagopa.transactions.configurations;

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
 * This class takes into account build mapping between checkout redirect psp id
 * and host url
 */
@Configuration
public class CheckoutRedirectUrlMapping {

    /**
     * Create a Map &lt String,URI &gt that will associate, to every handled PSP,
     * the backend URI to be used to perform Checkout Redirect payment flow api call
     *
     * @param pspToHandle   - set of all PSPs to be handled for Checkout Redirect
     *                      payment flow
     * @param pspUrlMapping - configuration parameter that contains PSP to URI
     *                      mapping
     * @return a configuration map for every PSPs
     */
    @Bean
    public Map<String, URI> checkoutRedirectUriMap(
                                                   @Value("${checkout.redirect.pspList}") Set<String> pspToHandle,
                                                   @Value(
                                                       "#{${checkout.redirect.pspUrlMapping}}"
                                                   ) Map<String, String> pspUrlMapping
    ) {
        Map<String, URI> checkoutRedirectUriMap = new HashMap<>();
        // URI.create throws IllegalArgumentException that will prevent module load for
        // invalid PSP URI configuration
        pspUrlMapping.forEach(
                (
                 pspId,
                 uri
                ) -> checkoutRedirectUriMap.put(pspId, URI.create(uri))
        );
        Set<String> missingKeys = pspToHandle
                .stream()
                .filter(Predicate.not(checkoutRedirectUriMap::containsKey))
                .collect(Collectors.toSet());
        if (!missingKeys.isEmpty()) {
            throw new IllegalStateException(
                    "Misconfigured checkout.redirect.pspUrlMapping, the following PSP are not configured: %s"
                            .formatted(missingKeys)
            );
        }
        return checkoutRedirectUriMap;

    }
}
