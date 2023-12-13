package it.pagopa.transactions.utils;

import io.jsonwebtoken.*;
import org.mockito.ArgumentMatcher;

import java.net.URI;

import static it.pagopa.ecommerce.commons.utils.JwtTokenUtils.*;

public class NpgNotificationUrlMatcher implements ArgumentMatcher<URI> {

    private String uriPrefix;
    private String transactionId;
    private String orderId;
    private String paymentMethodId;

    public NpgNotificationUrlMatcher(
            String uriPrefix,
            String transactionId,
            String orderId,
            String paymentMethodId
    ) {
        this.uriPrefix = uriPrefix;
        this.transactionId = transactionId;
        this.orderId = orderId;
        this.paymentMethodId = paymentMethodId;
    }

    @Override
    public boolean matches(URI right) {
        String uriString = right.toString();
        String jwtToken = uriString.substring(this.uriPrefix.length());
        int i = jwtToken.lastIndexOf('.');
        String withoutSignature = jwtToken.substring(0, i + 1);
        JwtParser parser = Jwts.parserBuilder().build();
        Jwt<Header, Claims> untrusted = parser.parseClaimsJwt(withoutSignature);
        return parser.isSigned(jwtToken) &&
                untrusted.getBody().get(TRANSACTION_ID_CLAIM).equals(transactionId) &&
                untrusted.getBody().get(ORDER_ID_CLAIM).equals(orderId) &&
                untrusted.getBody().get(PAYMENT_METHOD_ID_CLAIM).equals(paymentMethodId) &&
                right.toString().startsWith(uriPrefix);
    }
}
