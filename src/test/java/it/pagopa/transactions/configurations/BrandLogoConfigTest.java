package it.pagopa.transactions.configurations;

import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandLogoConfigTest {

    private final Map<String, String> cardBrandMap = Stream
            .of(CardAuthRequestDetailsDto.BrandEnum.values())
            .collect(
                    Collectors.toUnmodifiableMap(
                            CardAuthRequestDetailsDto.BrandEnum::toString,
                            "http://%s.cdn.uri"::formatted
                    )
            );

    private final BrandLogoConfig brandLogoConfig = new BrandLogoConfig();

    private static final String INVALID_URI = "http:\\invalidUri";

    private static final String INVALID_BRAND = "INVALID_BRAND";

    @Test
    void shouldBuildCardBrandMapSuccessfully() {
        Map<CardAuthRequestDetailsDto.BrandEnum, URI> brandMap = brandLogoConfig.brandConfMap(cardBrandMap);
        for (CardAuthRequestDetailsDto.BrandEnum brand : CardAuthRequestDetailsDto.BrandEnum.values()) {
            URI brandUri = brandMap.get(brand);
            assertEquals("http://%s.cdn.uri".formatted(brand), brandUri.toString());
        }
    }

    @Test
    void shouldThrowExceptionForMissingBrandKey() {
        Map<String, String> confMap = new HashMap<>();
        assertThrows(IllegalStateException.class, () -> brandLogoConfig.brandConfMap(confMap));

    }

    @Test
    void shouldThrowExceptionForMisconfiguredURI() {
        Map<String, String> confMap = Map.of(CardAuthRequestDetailsDto.BrandEnum.VISA.toString(), INVALID_URI);
        assertThrows(IllegalArgumentException.class, () -> brandLogoConfig.brandConfMap(confMap));

    }

    @Test
    void shouldThrowExceptionForMisconfiguredBrand() {
        Map<String, String> confMap = Map.of(INVALID_BRAND, "http://validUri");
        assertThrows(IllegalArgumentException.class, () -> brandLogoConfig.brandConfMap(confMap));
    }
}