package it.pagopa.transactions.commands.data;

import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthResponseEntityDto;
import reactor.core.publisher.Mono;

//TODO Classe wrapper per tutte le possibili risposte che otteniameo dai pgs client.
// Valutare l'introduzione di tuple e l'eliminazione di questa classe wrapper (pulizia del codice)
public class AuthResponseEntityDto {

    private XPayAuthResponseEntityDto requestXPayAuthorization;
    private PostePayAuthResponseEntityDto postePayAuth;

    public AuthResponseEntityDto() {}

    public AuthResponseEntityDto xPay(XPayAuthResponseEntityDto requestXPayAuthorization) {
        this.requestXPayAuthorization = requestXPayAuthorization;
        return this;
    }

    public AuthResponseEntityDto postePayAuth(PostePayAuthResponseEntityDto postePayAuth) {
        this.postePayAuth = postePayAuth;
        return this;
    }

    public XPayAuthResponseEntityDto getRequestXPayAuthorization() {
        return requestXPayAuthorization;
    }

    public PostePayAuthResponseEntityDto getPostePayAuth() {
        return postePayAuth;
    }

    public String getRequestId() {
        if(requestXPayAuthorization != null) {
            return requestXPayAuthorization.getRequestId();
        } else if (postePayAuth != null) {
            return postePayAuth.getRequestId();
        }
        return null;
    }

    public String getUrlRedirect() {
        if(requestXPayAuthorization != null) {
            return requestXPayAuthorization.getUrlRedirect();
        } else if (postePayAuth != null) {
            return postePayAuth.getUrlRedirect();
        }
        return null;
    }
}
