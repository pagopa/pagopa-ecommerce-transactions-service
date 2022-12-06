package it.pagopa.transactions.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.generated.ecommerce.gateway.v1.api.PostePayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
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
import org.mockito.Spy;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@ExtendWith(SpringExtension.class)
class PaymentGatewayClientTest {
    @InjectMocks
    private PaymentGatewayClient client;

    @Mock
    PostePayInternalApi paymentTransactionsControllerApi;

    private final UUID transactionIdUUID = UUID.randomUUID();

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnAuthorizationResponse() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                new PaymentToken("paymentToken"),
                new RptId("77777777777111111111111111111"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                new Email("foo@example.com"),
                null, null, TransactionStatusDto.ACTIVATED
        );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "paymentTypeCode",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                null,
                null,
                null,
                null
        );

        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()))
                .description(transaction.getDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        PostePayAuthResponseEntityDto apiResponse = new PostePayAuthResponseEntityDto()
                .channel("")
                .urlRedirect("https://example.com");

        /* preconditions */
        Mockito.when(paymentTransactionsControllerApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(Mono.just(apiResponse));

        /* test */
        StepVerifier.create(client.requestAuthorization(authorizationData))
                .expectNext(apiResponse)
                .verifyComplete();
    }

    @Test
    void shouldThrowAlreadyProcessedOn401() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                new PaymentToken("paymentToken"),
                new RptId("77777777777111111111111111111"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                new Email("foo@example.com"),
                null, null, TransactionStatusDto.ACTIVATED
        );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "paymentTypeCode",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                null,
                null,
                null,
                null
        );

        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()))
                .description(transaction.getDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(paymentTransactionsControllerApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(Mono.error(new WebClientResponseException("api error", HttpStatus.UNAUTHORIZED.value(), "Unauthorized", null, null, null)));

        /* test */
        StepVerifier.create(client.requestAuthorization(authorizationData))
                .expectErrorMatches(error ->
                        error instanceof AlreadyProcessedException &&
                                ((AlreadyProcessedException) error).getRptId().equals(transaction.getRptId()))
                .verify();
    }

    @Test
    void shouldThrowGatewayTimeoutOn504() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                new PaymentToken("paymentToken"),
                new RptId("77777777777111111111111111111"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                new Email("foo@example.com"),
                "faultCode",
                "faultCodeString",
                TransactionStatusDto.ACTIVATED
        );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "paymentTypeCode",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                null,
                null,
                null,
                null
        );

        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()))
                .description(transaction.getDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(paymentTransactionsControllerApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(Mono.error(new WebClientResponseException("api error", HttpStatus.GATEWAY_TIMEOUT.value(), "Gateway timeout", null, null, null)));

        /* test */
        StepVerifier.create(client.requestAuthorization(authorizationData))
                .expectErrorMatches(error -> error instanceof GatewayTimeoutException)
                .verify();
    }

    @Test
    void shouldThrowBadGatewayOn500() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                new PaymentToken("paymentToken"),
                new RptId("77777777777111111111111111111"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                new Email("foo@example.com"),
                null, null, TransactionStatusDto.ACTIVATED
        );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "paymentTypeCode",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                null,
                null,
                null,
                null
        );

        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()))
                .description(transaction.getDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(paymentTransactionsControllerApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(Mono.error(new WebClientResponseException("api error", HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error", null, null, null)));

        /* test */
        StepVerifier.create(client.requestAuthorization(authorizationData))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();
    }

    @Test
    void fallbackOnEmptyMdcInfoOnMapperError() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                new PaymentToken("paymentToken"),
                new RptId("77777777777111111111111111111"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                new Email("foo@example.com"),
                null, null, TransactionStatusDto.ACTIVATED
        );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "paymentTypeCode",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                null,
                null,
                null,
                null
        );

        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()))
                .description(transaction.getDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String encodedMdcFields = "";

        PostePayAuthResponseEntityDto apiResponse = new PostePayAuthResponseEntityDto()
                .channel("")
                .urlRedirect("https://example.com");

        /* preconditions */
        Mockito.when(objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID))).thenThrow(new JsonProcessingException(""){});
        Mockito.when(paymentTransactionsControllerApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(Mono.just(apiResponse));

        /* test */
        StepVerifier.create(client.requestAuthorization(authorizationData))
                .expectNext(apiResponse)
                .verifyComplete();

    }
}
