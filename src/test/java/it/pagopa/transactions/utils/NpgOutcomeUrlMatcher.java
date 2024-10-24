package it.pagopa.transactions.utils;

import io.jsonwebtoken.*;
import org.mockito.ArgumentMatcher;

import java.net.URI;

import static it.pagopa.ecommerce.commons.utils.JwtTokenUtils.*;
import static it.pagopa.transactions.utils.UrlUtils.getParametersAsMap;
import static it.pagopa.transactions.utils.UrlUtils.urlsEqualsWithRandomParam;

public class NpgOutcomeUrlMatcher implements ArgumentMatcher<URI> {

    private String actualUri;
    private String transactionId;
    private String orderId;
    private String paymentMethodId;

    public NpgOutcomeUrlMatcher(
            String actualUri,
            String transactionId,
            String orderId,
            String paymentMethodId
    ) {
        this.actualUri = actualUri;
        this.transactionId = transactionId;
        this.orderId = orderId;
        this.paymentMethodId = paymentMethodId;
    }

    @Override
    public boolean matches(URI uri) {
        String jwtToken = getParametersAsMap(uri.getFragment()).get("sessionToken");
        int i = jwtToken.lastIndexOf('.');
        String withoutSignature = jwtToken.substring(0, i + 1);
        JwtParser parser = Jwts.parserBuilder().build();
        Jwt<Header, Claims> untrusted = parser.parseClaimsJwt(withoutSignature);
        String expectedUri = uri.toString()
                .substring(0, uri.toString().indexOf("sessionToken=") + "sessionToken=".length()) + "sessionToken";
        boolean result;
        if (orderId != null) {
            result = parser.isSigned(jwtToken) &&
                    untrusted.getBody().get(TRANSACTION_ID_CLAIM).equals(transactionId) &&
                    untrusted.getBody().get(ORDER_ID_CLAIM).equals(orderId) &&
                    untrusted.getBody().get(PAYMENT_METHOD_ID_CLAIM).equals(paymentMethodId) &&
                    urlsEqualsWithRandomParam(this.actualUri, expectedUri);
        } else {
            result = parser.isSigned(jwtToken) &&
                    untrusted.getBody().get(TRANSACTION_ID_CLAIM).equals(transactionId) &&
                    untrusted.getBody().get(PAYMENT_METHOD_ID_CLAIM).equals(paymentMethodId) &&
                    urlsEqualsWithRandomParam(this.actualUri, expectedUri);
        }
        return result;
    }
}
