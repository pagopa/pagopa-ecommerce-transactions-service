package it.pagopa.transactions.configurations;

import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
public class BrandLogoConfig {

    @Bean
    @Qualifier("brandConfMap")
    public Map<CardAuthRequestDetailsDto.BrandEnum, URI> brandConfMap(
                                                                      @Value(
                                                                          "#{${logo.cardBrandMapping}}"
                                                                      ) Map<String, String> cardBrandLogoMapping
    ) {
        Map<CardAuthRequestDetailsDto.BrandEnum, URI> logoMap = new EnumMap<>(
                CardAuthRequestDetailsDto.BrandEnum.class
        );
        for (Map.Entry<String, String> entry : cardBrandLogoMapping.entrySet()) {
            // both below conversion methods thrown IllegalArgumentException in case of
            // misconfigured brand/uri, preventing the module startup in case of missing or
            // invalid configuration parameters
            logoMap.put(CardAuthRequestDetailsDto.BrandEnum.fromValue(entry.getKey()), URI.create(entry.getValue()));
        }
        Set<CardAuthRequestDetailsDto.BrandEnum> missingConfKey = Stream
                .of(CardAuthRequestDetailsDto.BrandEnum.values())
                .filter(Predicate.not(logoMap::containsKey))
                .collect(Collectors.toSet());
        if (!missingConfKey.isEmpty()) {
            throw new IllegalStateException(
                    "Misconfigured logo.cardBrandMapping, the following brands are not configured: %s"
                            .formatted(missingConfKey)
            );
        }
        return logoMap;
    }

    @Bean
    @Qualifier("npgPaymentCircuitLogoMap")
    public Map<String, URI> npgPaymentCircuitLogoMap(
                                                     @Value(
                                                         "#{${logo.npgPaymentCircuitMapping}}"
                                                     ) Map<String, String> npgPaymentCircuitMapping
    ) {
        Map<String, URI> logoMap = new HashMap<>();
        for (Map.Entry<String, String> entry : npgPaymentCircuitMapping.entrySet()) {
            logoMap.put(entry.getKey(), URI.create(entry.getValue()));
        }
        return logoMap;
    }
}
