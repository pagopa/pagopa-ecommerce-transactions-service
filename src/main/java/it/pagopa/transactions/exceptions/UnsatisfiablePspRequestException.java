package it.pagopa.transactions.exceptions;

import it.pagopa.ecommerce.commons.domain.v2.PaymentToken;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.CONFLICT)
public class UnsatisfiablePspRequestException extends Exception {
    private final PaymentToken paymentToken;
    private final RequestAuthorizationRequestDto.LanguageEnum language;
    private final int requestedFee;

    public UnsatisfiablePspRequestException(
            PaymentToken paymentToken,
            RequestAuthorizationRequestDto.LanguageEnum language,
            int requestedFee
    ) {
        this.paymentToken = paymentToken;
        this.language = language;
        this.requestedFee = requestedFee;
    }

    public PaymentToken getPaymentToken() {
        return paymentToken;
    }

    public RequestAuthorizationRequestDto.LanguageEnum getLanguage() {
        return language;
    }

    public int getRequestedFee() {
        return requestedFee;
    }
}
