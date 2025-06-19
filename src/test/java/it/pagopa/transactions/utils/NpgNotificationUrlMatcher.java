package it.pagopa.transactions.utils;

import io.jsonwebtoken.*;
import it.pagopa.ecommerce.commons.client.JwtIssuerClient;
import org.mockito.ArgumentMatcher;

import java.net.URI;

import static it.pagopa.transactions.utils.UrlUtils.getParametersAsMap;
import static it.pagopa.transactions.utils.UrlUtils.urlsEqualsWithRandomParam;

public class NpgNotificationUrlMatcher implements ArgumentMatcher<URI> {

    private String actualUri;
    private String transactionId;
    private String orderId;
    private String paymentMethodId;

    public NpgNotificationUrlMatcher(
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
        String jwtToken = getParametersAsMap(uri.getQuery()).get("sessionToken");
        int i = jwtToken.lastIndexOf('.');
        String withoutSignature = jwtToken.substring(0, i + 1);
        JwtParser parser = Jwts.parserBuilder().build();
        Jwt<Header, Claims> untrusted = parser.parseClaimsJwt(withoutSignature);
        String expectedUri = uri.toString()
                .substring(0, uri.toString().indexOf("sessionToken=") + "sessionToken=".length()) + "sessionToken";
        return parser.isSigned(jwtToken) &&
                untrusted.getBody().get(JwtIssuerClient.TRANSACTION_ID_CLAIM).equals(transactionId) &&
                untrusted.getBody().get(JwtIssuerClient.ORDER_ID_CLAIM).equals(orderId) &&
                untrusted.getBody().get(JwtIssuerClient.PAYMENT_METHOD_ID_CLAIM).equals(paymentMethodId) &&
                urlsEqualsWithRandomParam(this.actualUri, expectedUri);
    }
}
