package it.pagopa.transactions.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PaymentTokenTest {
    @Test
    void shouldConstructPaymentToken() {
        String rawPaymentToken = "payment_token";
        PaymentToken paymentToken = new PaymentToken(rawPaymentToken);

        assertEquals(paymentToken.value(), rawPaymentToken);
    }
}
