package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenResponseDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;

import static it.pagopa.transactions.utils.UrlUtils.urlsEqualsWithRandomParam;

public class TransactionAuthorizationHandlerCommon {
    public static final String MOCK_JWT = "eyJraWQiOiJKeVI2cG5meTlSQ2lITHVBcFhFY0VGUUU5b1pTQTVtak1mMXBuNENIWWJrIiwiYWxnIjoiUlMyNTYifQ.eyJvcmRlcklkIjoiRTE3NDg2MDAyNTIyMzRIbG45IiwidHJhbnNhY3Rpb25JZCI6Ijk5MjFhZTQ0YTFjZDQ2MDNhNGZjZDk5YjAzN2YyMjkxIiwianRpIjoiNTFiNjY1ODYtYzNhZi00MjViLWFjYzYtODU3YTJiZTc0M2NjIiwiaWF0IjoxNzQ4NjAwMjYzLCJleHAiOjE3NDg2MDExNjMsImF1ZCI6ImVjb21tZXJjZSIsImlzcyI6InBhZ29wYS1lY29tbWVyY2Utand0LWlzc3Vlci1zZXJ2aWNlIn0.GzP24d_TftY5ZVhn9dFuaZYnkUwtgasYLY3q9WtHlMBESLzr0c4CbSgBtZNodzX8z-7kpMCmpTtt1p4MQHAVlnzyUajdDPFD-fQDzGczwVQpysdoniQDNv1Eghg0dkq8dEaZ0-i30cQnzmUBcZRMfnsgD8FNtDzMoOqc7YdxZSYn81vscwBYeFB2gvY1f3FHKss7Zjc4NoXanssQ5aDadiQBp8griWXXp2L3FhTyQmo7ao9itRhFfM2TCvQjUFjXRneJpMTXYPypBTkjGbjiZKRJO54qige1eseEFEEpcL3Pnm1EpKryF_D4ldhrWPaoO12E76Mz2EMtEmndvAK8cQ";
    public static final CreateTokenResponseDto createTokenResponseDto = new CreateTokenResponseDto().token(MOCK_JWT);

    public static final int TOKEN_VALIDITY_TIME_SECONDS = 900;

    public static boolean requestAuthResponseDtoComparator(
                                                           RequestAuthorizationResponseDto actual,
                                                           RequestAuthorizationResponseDto expected
    ) {
        return actual.getAuthorizationRequestId().equals(expected.getAuthorizationRequestId()) &&
                urlsEqualsWithRandomParam(actual.getAuthorizationUrl(), expected.getAuthorizationUrl());

    }
}
