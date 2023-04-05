package it.pagopa.transactions.utils;

/**
 * Enum class to handle different payment gateways
 */
public enum PaymentGateway {
    VPOS("VPOS"),
    XPAY("XPAY"),
    POSTEPAY("POSTEPAY");

    public final String value;

    PaymentGateway(String paymentGateway) {
        this.value = paymentGateway;
    }
}
