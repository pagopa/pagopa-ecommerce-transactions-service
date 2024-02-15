package it.pagopa.transactions.commands.data;

import java.util.Optional;

/**
 * Record mapping authorization request output payment gateways fields
 *
 * @param authorizationId            the authorization id
 * @param authorizationUrl           the authorization URL where user has to be
 *                                   redirected
 * @param npgSessionId               optional npg session id
 * @param npgConfirmSessionId        optional npg confirm session id
 * @param authorizationTimeoutMillis authorization timeout in milliseconds
 */
public record AuthorizationOutput(
        String authorizationId,
        String authorizationUrl,
        Optional<String> npgSessionId,
        Optional<String> npgConfirmSessionId,
        Optional<Integer> authorizationTimeoutMillis
) {

}
