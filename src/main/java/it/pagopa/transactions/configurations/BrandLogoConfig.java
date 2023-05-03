package it.pagopa.transactions.configurations;

import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
@Slf4j
public class BrandLogoConfig {

    @Bean
    @Qualifier("brandConfMap")
    public Map<CardAuthRequestDetailsDto.BrandEnum, URI> brandConfMap(
                                                                      @Value(
                                                                          "#{${logo.cardBrandMapping}}"
                                                                      ) Map<String, String> cardBrandLogoMapping
    ) {
        if (cardBrandLogoMapping.isEmpty()) {
            throw new IllegalStateException("logo.cardBrandMapping not configured");
        }
        Map<CardAuthRequestDetailsDto.BrandEnum, URI> logoMap = new EnumMap<>(
                CardAuthRequestDetailsDto.BrandEnum.class
        );
        for (Map.Entry<String, String> entry : cardBrandLogoMapping.entrySet()) {
            // below conversion methods both throw IllegalArgumentException in case of
            // misconfigured brand/uri, preventing the module startup
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
}
