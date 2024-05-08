package it.pagopa.transactions.exceptions;

public class PaymentMethodNotFoundException extends RuntimeException {
    public final String paymentMethodId;
    public final String clientId;

    public PaymentMethodNotFoundException(
            String paymentMethodId,
            String clientId
    ) {
        super("Could not find payment method with id %s for client %s".formatted(paymentMethodId, clientId));

        this.paymentMethodId = paymentMethodId;
        this.clientId = clientId;
    }
}
