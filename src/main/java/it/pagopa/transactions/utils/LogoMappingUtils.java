package it.pagopa.transactions.utils;

import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.CardsAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.WalletAuthRequestDetailsDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.configurations.BrandLogoConfig;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;

@Component
public class LogoMappingUtils {

    private final Map<CardAuthRequestDetailsDto.BrandEnum, URI> pgsBrandConfMap;

    private final Map<String, URI> npgPaymentCircuitLogoMap;

    @Autowired
    public LogoMappingUtils(
            Map<CardAuthRequestDetailsDto.BrandEnum, URI> pgsBrandConfMap,
            Map<String, URI> npgPaymentCircuitLogoMap
    ) {
        this.pgsBrandConfMap = pgsBrandConfMap;
        this.npgPaymentCircuitLogoMap = npgPaymentCircuitLogoMap;
    }

    public URI getLogo(AuthorizationRequestData authRequestedData) {
        RequestAuthorizationRequestDetailsDto authorizationRequestDetailsDto = authRequestedData.authDetails();
        return switch (authorizationRequestDetailsDto.getClass().getSimpleName()) {
            case "CardAuthRequestDetailsDto" -> pgsBrandConfMap.get(((CardAuthRequestDetailsDto)authorizationRequestDetailsDto).getBrand());
            case "CardsAuthRequestDetailsDto", "WalletAuthRequestDetailsDto"-> npgPaymentCircuitLogoMap.getOrDefault(
                    authRequestedData.brand(),
                    npgPaymentCircuitLogoMap.get(BrandLogoConfig.UNKNOWN_LOGO_KEY)
            );
            case default -> throw new InvalidRequestException("Authorization request detail type not valid");
        };
    }
}
