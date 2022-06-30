package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.gateway.v1.api.PaymentTransactionsControllerApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.GatewayTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(SpringExtension.class)
public class PaymentGatewayClientTest {
    @InjectMocks
    private PaymentGatewayClient client;

    @Mock
    PaymentTransactionsControllerApi paymentTransactionsControllerApi;

    @Test
    void shouldReturnAuthorizationResponse() {
        Transaction transaction = new Transaction(
                new TransactionId("transactionId"),
                new PaymentToken("paymentToken"),
                new RptId("rptId"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                TransactionStatusDto.INITIALIZED
        );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "paymentTypeCode",
                "brokerName",
                "pspChannelCode",
                UUID.randomUUID()
        );

        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()))
                .description(transaction.getDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(0L);

        String mdcInfo = "mdcInfo";

        PostePayAuthResponseEntityDto apiResponse = new PostePayAuthResponseEntityDto()
                .channel("")
                .urlRedirect("https://example.com");

        RequestAuthorizationResponseDto expected = new RequestAuthorizationResponseDto()
                .authorizationUrl("https://example.com");

        /* preconditions */
        Mockito.when(paymentTransactionsControllerApi.authRequest(any(), eq(postePayAuthRequest), eq(mdcInfo)))
                .thenReturn(Mono.just(apiResponse));

        /* test */
        assertEquals(expected, client.requestAuthorization(authorizationData).block());
    }

    @Test
    void shouldThrowAlreadyProcessedOn401() {
        Transaction transaction = new Transaction(
                new TransactionId("transactionId"),
                new PaymentToken("paymentToken"),
                new RptId("rptId"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                TransactionStatusDto.INITIALIZED
        );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "paymentTypeCode",
                "brokerName",
                "pspChannelCode",
                UUID.randomUUID()
        );

        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()))
                .description(transaction.getDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(0L);

        String mdcInfo = "mdcInfo";

        /* preconditions */
        Mockito.when(paymentTransactionsControllerApi.authRequest(any(), eq(postePayAuthRequest), eq(mdcInfo)))
                .thenReturn(Mono.error(new WebClientResponseException("api error", HttpStatus.UNAUTHORIZED.value(), "Unauthorized", null, null, null)));

        /* test */
        StepVerifier.create(client.requestAuthorization(authorizationData))
                .expectErrorMatches(error ->
                        error instanceof AlreadyProcessedException &&
                                ((AlreadyProcessedException) error).getRptId().equals(transaction.getRptId()))
                .verify();
    }

    @Test
    void shouldThrowGatewayTimeoutOn504() {
        Transaction transaction = new Transaction(
                new TransactionId("transactionId"),
                new PaymentToken("paymentToken"),
                new RptId("rptId"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                TransactionStatusDto.INITIALIZED
        );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "paymentTypeCode",
                "brokerName",
                "pspChannelCode",
                UUID.randomUUID()
        );

        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()))
                .description(transaction.getDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(0L);

        String mdcInfo = "mdcInfo";

        /* preconditions */
        Mockito.when(paymentTransactionsControllerApi.authRequest(any(), eq(postePayAuthRequest), eq(mdcInfo)))
                .thenReturn(Mono.error(new WebClientResponseException("api error", HttpStatus.GATEWAY_TIMEOUT.value(), "Gateway timeout", null, null, null)));

        /* test */
        StepVerifier.create(client.requestAuthorization(authorizationData))
                .expectErrorMatches(error -> error instanceof GatewayTimeoutException)
                .verify();
    }

    @Test
    void shouldThrowBadGatewayOn500() {
        Transaction transaction = new Transaction(
                new TransactionId("transactionId"),
                new PaymentToken("paymentToken"),
                new RptId("rptId"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                TransactionStatusDto.INITIALIZED
        );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "paymentTypeCode",
                "brokerName",
                "pspChannelCode",
                UUID.randomUUID()
        );

        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()))
                .description(transaction.getDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(0L);

        String mdcInfo = "mdcInfo";

        /* preconditions */
        Mockito.when(paymentTransactionsControllerApi.authRequest(any(), eq(postePayAuthRequest), eq(mdcInfo)))
                .thenReturn(Mono.error(new WebClientResponseException("api error", HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error", null, null, null)));

        /* test */
        StepVerifier.create(client.requestAuthorization(authorizationData))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();
    }
}
