package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenResponseDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.transactions.configurations.SecretsConfigurations;

import javax.crypto.SecretKey;

import static it.pagopa.transactions.utils.UrlUtils.urlsEqualsWithRandomParam;

public class TransactionAuthorizationHandlerCommon {
    public static final String MOCK_JWT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw";
    public static final CreateTokenResponseDto createTokenResponseDto = new CreateTokenResponseDto().token(MOCK_JWT);
    private static final String STRONG_KEY = "ODMzNUZBNTZENDg3NTYyREUyNDhGNDdCRUZDNzI3NDMzMzQwNTFEREZGQ0MyQzA5Mjc1RjY2NTQ1NDk5MDMxNzU5NDc0NUVFMTdDMDhGNzk4Q0Q3RENFMEJBODE1NURDREExNEY2Mzk4QzFEMTU0NTExNjUyMEExMzMwMTdDMDk";

    public static final int TOKEN_VALIDITY_TIME_SECONDS = 900;

    public static final SecretKey ECOMMERCE_JWT_SIGNING_KEY = new SecretsConfigurations()
            .ecommerceSigningKey(STRONG_KEY);

    public static boolean requestAuthResponseDtoComparator(
                                                           RequestAuthorizationResponseDto actual,
                                                           RequestAuthorizationResponseDto expected
    ) {
        return actual.getAuthorizationRequestId().equals(expected.getAuthorizationRequestId()) &&
                urlsEqualsWithRandomParam(actual.getAuthorizationUrl(), expected.getAuthorizationUrl());

    }
}
