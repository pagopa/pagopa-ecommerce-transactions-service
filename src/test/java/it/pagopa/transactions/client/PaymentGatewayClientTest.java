package it.pagopa.transactions.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.ecommerce.gateway.v1.api.PostePayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.XPayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthResponseEntityDto;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.PostePayAuthRequestDetailsDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.GatewayTimeoutException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
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
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@ExtendWith(SpringExtension.class)
class PaymentGatewayClientTest {
    @InjectMocks
    private PaymentGatewayClient client;

    @Mock
    PostePayInternalApi paymentTransactionsControllerApi;

    @Mock
    XPayInternalApi xPayInternalApi;

    private final UUID transactionIdUUID = UUID.randomUUID();

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnAuthorizationEmptyOptionalResponseCP_VPOS() throws JsonProcessingException {
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
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                "VPOS",
                new PostePayAuthRequestDetailsDto().detailType("VPOS").accountEmail("test@test.it")
        );


        /* test */
        StepVerifier.create(client.requestGeneralAuthorization(authorizationData))
                .expectNextMatches(s -> s.getT1().isEmpty() && s.getT2().isEmpty())
                .verifyComplete();
    }

    @Test
    void shouldReturnAuthorizationResponseCP_XPAY() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                new PaymentToken("paymentToken"),
                new RptId("77777777777111111111111111111"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                new Email("foo@example.com"),
                null, null, TransactionStatusDto.ACTIVATED
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto().cvv("345").pan("16589654852").expiryDate(LocalDate.of(2030, Month.DECEMBER,31));
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                "XPAY",
                cardDetails
        );

        XPayAuthRequestDto xPayAuthRequestDto = new XPayAuthRequestDto()
                .cvv(cardDetails.getCvv())
                .pan(cardDetails.getPan())
                .exipiryDate(cardDetails.getExpiryDate().format(DateTimeFormatter.ofPattern("yyyyMM")))
                .idTransaction(transactionIdUUID.toString())
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()));

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        XPayAuthResponseEntityDto xPayResponse = new XPayAuthResponseEntityDto()
                .requestId("requestId")
                .urlRedirect("https://example.com");

        /* preconditions */
        Mockito.when(xPayInternalApi.authRequestXpay(xPayAuthRequestDto, encodedMdcFields))
                .thenReturn(Mono.just(xPayResponse));

        /* test */
        StepVerifier.create(client.requestGeneralAuthorization(authorizationData))
                .assertNext(response -> response.getT2().get().equals(xPayResponse))
                .verifyComplete();
    }

    @Test
    void shouldReturnAuthorizationResponsePPAY() throws JsonProcessingException {
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
                "PPAY",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
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

        PostePayAuthResponseEntityDto postePayResponse = new PostePayAuthResponseEntityDto()
                .channel("")
                .urlRedirect("https://example.com");

        /* preconditions */
        Mockito.when(paymentTransactionsControllerApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(Mono.just(postePayResponse));

        /* test */
        StepVerifier.create(client.requestGeneralAuthorization(authorizationData))
                .assertNext(response -> response.getT1().get().equals(Mono.just(postePayResponse)))
                .verifyComplete();
    }

    @Test
    void shouldThrowAlreadyProcessedOn401CP_XPAY() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                new PaymentToken("paymentToken"),
                new RptId("77777777777111111111111111111"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                new Email("foo@example.com"),
                null, null, TransactionStatusDto.ACTIVATED
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto().cvv("345").pan("16589654852").expiryDate(LocalDate.of(2030, Month.DECEMBER,31));
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                "XPAY",
                cardDetails
        );

        XPayAuthRequestDto xPayAuthRequestDto = new XPayAuthRequestDto()
                .cvv(cardDetails.getCvv())
                .pan(cardDetails.getPan())
                .exipiryDate(cardDetails.getExpiryDate().format(DateTimeFormatter.ofPattern("yyyyMM")))
                .idTransaction(transactionIdUUID.toString())
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()));

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(xPayInternalApi.authRequestXpay(xPayAuthRequestDto, encodedMdcFields))
                .thenReturn(Mono.error(new WebClientResponseException("api error", HttpStatus.UNAUTHORIZED.value(), "Unauthorized", null, null, null)));

        /* test */
        StepVerifier.create(client.requestGeneralAuthorization(authorizationData))
                .expectErrorMatches(error ->
                        error instanceof AlreadyProcessedException &&
                                ((AlreadyProcessedException) error).getRptId().equals(transaction.getRptId()))
                .verify();
    }

    @Test
    void shouldThrowAlreadyProcessedOn401PPAY() throws JsonProcessingException {
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
                "PPAY",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
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
        StepVerifier.create(client.requestGeneralAuthorization(authorizationData))
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
                "PPAY",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
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
        StepVerifier.create(client.requestGeneralAuthorization(authorizationData))
                .expectErrorMatches(error ->
                        error instanceof  GatewayTimeoutException
                )
                .verify();
    }

    @Test
    void shouldThrowBadGatewayOn500PPAY() throws JsonProcessingException {
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
                "PPAY",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
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
        StepVerifier.create(client.requestGeneralAuthorization(authorizationData))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();
    }

    @Test
    void shouldThrowBadGatewayOn500CP_XPAY() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                new PaymentToken("paymentToken"),
                new RptId("77777777777111111111111111111"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                new Email("foo@example.com"),
                null, null, TransactionStatusDto.ACTIVATED
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto().cvv("345").pan("16589654852").expiryDate(LocalDate.of(2030, Month.DECEMBER,31));
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                "XPAY",
                cardDetails
        );

        XPayAuthRequestDto xPayAuthRequestDto = new XPayAuthRequestDto()
                .cvv(cardDetails.getCvv())
                .pan(cardDetails.getPan())
                .exipiryDate(cardDetails.getExpiryDate().format(DateTimeFormatter.ofPattern("yyyyMM")))
                .idTransaction(transactionIdUUID.toString())
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()));

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(xPayInternalApi.authRequestXpay(xPayAuthRequestDto, encodedMdcFields))
                .thenReturn(Mono.error(new WebClientResponseException("api error", HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error", null, null, null)));

        /* test */
        StepVerifier.create(client.requestGeneralAuthorization(authorizationData))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();
    }

    @Test
    void fallbackOnEmptyMdcInfoOnMapperErrorPPAY() throws JsonProcessingException {
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
                "PPAY",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                null,
                null
        );

        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()))
                .description(transaction.getDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String encodedMdcFields = "";

        PostePayAuthResponseEntityDto postePayResponse = new PostePayAuthResponseEntityDto()
                .channel("")
                .urlRedirect("https://example.com");


        /* preconditions */
        Mockito.when(objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID))).thenThrow(new JsonProcessingException(""){});
        Mockito.when(paymentTransactionsControllerApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(Mono.just(postePayResponse));

        /* test */
        StepVerifier.create(client.requestGeneralAuthorization(authorizationData))
                .assertNext(response -> response.getT1().get().equals(postePayResponse))
                .verifyComplete();

    }
    @Test
    void fallbackOnEmptyMdcInfoOnMapperErrorCP_XPAY() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                new PaymentToken("paymentToken"),
                new RptId("77777777777111111111111111111"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                new Email("foo@example.com"),
                null, null, TransactionStatusDto.ACTIVATED
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto().cvv("345").pan("16589654852").expiryDate(LocalDate.of(2030, Month.DECEMBER,31));
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                10,
                "paymentInstrumentId",
                "pspId",
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                "XPAY",
                cardDetails
        );

        XPayAuthRequestDto xPayAuthRequestDto = new XPayAuthRequestDto()
                .cvv(cardDetails.getCvv())
                .pan(cardDetails.getPan())
                .exipiryDate(cardDetails.getExpiryDate().format(DateTimeFormatter.ofPattern("yyyyMM")))
                .idTransaction(transactionIdUUID.toString())
                .grandTotal(BigDecimal.valueOf(transaction.getAmount().value() + authorizationData.fee()));

        String encodedMdcFields = "";

        XPayAuthResponseEntityDto xPayResponse = new XPayAuthResponseEntityDto()
                .requestId("requestId")
                .urlRedirect("https://example.com");


        /* preconditions */
        Mockito.when(objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID))).thenThrow(new JsonProcessingException("") {
        });
        Mockito.when(xPayInternalApi.authRequestXpay(xPayAuthRequestDto, encodedMdcFields))
                .thenReturn(Mono.just(xPayResponse));

        /* test */
        StepVerifier.create(client.requestGeneralAuthorization(authorizationData))
                .assertNext(response -> response.getT2().get().equals(xPayResponse))
                .verifyComplete();

    }

    @Test
    void shouldThrowInvalidRequestWhenCardDetailsAreMissing_XPAY() throws JsonProcessingException {
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
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                "XPAY",
                null
        );


        /* test */
        StepVerifier.create(client.requestGeneralAuthorization(authorizationData))
                .expectErrorMatches(exception -> exception instanceof InvalidRequestException);

    }
}
