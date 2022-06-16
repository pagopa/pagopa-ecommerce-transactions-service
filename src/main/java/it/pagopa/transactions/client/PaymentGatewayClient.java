package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.gateway.v1.api.PaymentTransactionsControllerApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class PaymentGatewayClient {

    @Autowired
    PaymentTransactionsControllerApi paymentTransactionGatewayApi;

    public Mono<PostePayAuthResponseEntityDto> requestAuthorization() {
        return paymentTransactionGatewayApi.authRequest(
                UUID.randomUUID(),
                new PostePayAuthRequestDto()
                        .grandTotal(BigDecimal.valueOf(0))
                        .description("")
                        .paymentChannel("")
                        .idTransaction(0L),
                "mdcInfo"
        );
    }
}
