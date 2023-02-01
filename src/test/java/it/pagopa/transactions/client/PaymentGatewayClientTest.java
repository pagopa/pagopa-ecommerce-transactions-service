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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
class PaymentGatewayClientTest {
    @InjectMocks
    private PaymentGatewayClient client;

    @Mock
    PostePayInternalApi postePayInternalApi;

    @Mock
    XPayInternalApi xPayInternalApi;

    private final UUID transactionIdUUID = UUID.randomUUID();

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldNotCallAuthorizationGatewayWithInvalidDetailType() {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
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
                new PostePayAuthRequestDetailsDto().detailType("invalid").accountEmail("test@test.it")
        );

        /* test */
        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verifyNoInteractions(postePayInternalApi, xPayInternalApi);
    }

    @Test
    void shouldReturnAuthorizationResponseForCreditCardWithXPay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .cvv("345")
                .pan("16589654852")
                .expiryDate("203012")
                .detailType("card")
                .holderName("John Doe")
                .brand("VISA")
                .threeDsData("threeDsData");
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
                .exipiryDate(cardDetails.getExpiryDate())
                .idTransaction(transactionIdUUID.toString())
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                );

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        XPayAuthResponseEntityDto xPayResponse = new XPayAuthResponseEntityDto()
                .requestId("requestId")
                .urlRedirect("https://example.com");

        /* preconditions */
        Mockito.when(xPayInternalApi.authRequestXpay(xPayAuthRequestDto, encodedMdcFields))
                .thenReturn(Mono.just(xPayResponse));

        /* test */
        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNext(xPayResponse)
                .verifyComplete();

        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(1)).authRequestXpay(any(), any());
        verify(postePayInternalApi, times(0)).authRequest(any(), any(), any());
    }

    @Test
    void shouldReturnAuthorizationResponseWithPostePay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        PostePayAuthResponseEntityDto postePayResponse = new PostePayAuthResponseEntityDto()
                .channel("")
                .urlRedirect("https://example.com");

        /* preconditions */
        Mockito.when(postePayInternalApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(Mono.just(postePayResponse));

        /* test */
        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectNext(postePayResponse)
                .verifyComplete();

        verify(xPayInternalApi, times(0)).authRequestXpay(any(), any());
        verify(postePayInternalApi, times(1)).authRequest(any(), any(), any());
    }

    @Test
    void shouldThrowAlreadyProcessedOn401ForCreditCardWithXpay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .detailType("card")
                .cvv("345")
                .pan("16589654852")
                .expiryDate("203012")
                .brand("VISA")
                .threeDsData("threeDsData");
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
                .exipiryDate(cardDetails.getExpiryDate())
                .idTransaction(transactionIdUUID.toString())
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                );

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(xPayInternalApi.authRequestXpay(xPayAuthRequestDto, encodedMdcFields))
                .thenReturn(
                        Mono.error(
                                new WebClientResponseException(
                                        "api error",
                                        HttpStatus.UNAUTHORIZED.value(),
                                        "Unauthorized",
                                        null,
                                        null,
                                        null
                                )
                        )
                );

        /* test */

        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectErrorMatches(
                        error -> error instanceof AlreadyProcessedException &&
                                ((AlreadyProcessedException) error).getTransactionId()
                                        .equals(transaction.getTransactionId())
                )
                .verify();

        verify(xPayInternalApi, times(1)).authRequestXpay(any(), any());
        verify(postePayInternalApi, times(0)).authRequest(any(), any(), any());
    }

    @Test
    void shouldThrowAlreadyProcessedOn401ForPostePay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(postePayInternalApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(
                        Mono.error(
                                new WebClientResponseException(
                                        "api error",
                                        HttpStatus.UNAUTHORIZED.value(),
                                        "Unauthorized",
                                        null,
                                        null,
                                        null
                                )
                        )
                );

        /* test */
        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectErrorMatches(
                        error -> error instanceof AlreadyProcessedException &&
                                ((AlreadyProcessedException) error).getTransactionId()
                                        .equals(transaction.getTransactionId())
                )
                .verify();

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(0)).authRequestXpay(any(), any());
        verify(postePayInternalApi, times(1)).authRequest(any(), any(), any());
    }

    @Test
    void shouldThrowGatewayTimeoutOn504() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                new Email("foo@example.com"),
                "faultCode",
                "faultCodeString",
                TransactionStatusDto.ACTIVATED,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(postePayInternalApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(
                        Mono.error(
                                new WebClientResponseException(
                                        "api error",
                                        HttpStatus.GATEWAY_TIMEOUT.value(),
                                        "Gateway timeout",
                                        null,
                                        null,
                                        null
                                )
                        )
                );

        /* test */
        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectErrorMatches(
                        error -> error instanceof GatewayTimeoutException
                )
                .verify();

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(0)).authRequestXpay(any(), any());
        verify(postePayInternalApi, times(1)).authRequest(any(), any(), any());
    }

    @Test
    void shouldThrowBadGatewayOn500ForPostePay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(postePayInternalApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(
                        Mono.error(
                                new WebClientResponseException(
                                        "api error",
                                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                        "Internal server error",
                                        null,
                                        null,
                                        null
                                )
                        )
                );

        /* test */
        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(0)).authRequestXpay(any(), any());
        verify(postePayInternalApi, times(1)).authRequest(any(), any(), any());
    }

    @Test
    void shouldThrowBadGatewayOn500ForCreditCardWithXPay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .cvv("345")
                .pan("16589654852")
                .expiryDate("203012")
                .brand("VISA")
                .threeDsData("threeDsData");
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
                .exipiryDate(cardDetails.getExpiryDate())
                .idTransaction(transactionIdUUID.toString())
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                );

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(xPayInternalApi.authRequestXpay(xPayAuthRequestDto, encodedMdcFields))
                .thenReturn(
                        Mono.error(
                                new WebClientResponseException(
                                        "api error",
                                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                        "Internal server error",
                                        null,
                                        null,
                                        null
                                )
                        )
                );

        /* test */
        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();

        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(1)).authRequestXpay(any(), any());
        verify(postePayInternalApi, times(0)).authRequest(any(), any(), any());
    }

    @Test
    void fallbackOnEmptyMdcInfoOnMapperErrorForPostePay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String encodedMdcFields = "";

        PostePayAuthResponseEntityDto postePayResponse = new PostePayAuthResponseEntityDto()
                .channel("")
                .urlRedirect("https://example.com");

        /* preconditions */
        Mockito.when(objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID)))
                .thenThrow(new JsonProcessingException("") {
                });
        Mockito.when(postePayInternalApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(Mono.just(postePayResponse));

        /* test */
        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectNext(postePayResponse)
                .verifyComplete();

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(0)).authRequestXpay(any(), any());
        verify(postePayInternalApi, times(1)).authRequest(any(), any(), any());
    }

    @Test
    void fallbackOnEmptyMdcInfoOnMapperErrorForCreditCardWithXPay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .cvv("345")
                .pan("16589654852")
                .expiryDate("203012")
                .brand("VISA")
                .threeDsData("threeDsData");
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
                .exipiryDate(cardDetails.getExpiryDate())
                .idTransaction(transactionIdUUID.toString())
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                );

        String encodedMdcFields = "";

        XPayAuthResponseEntityDto xPayResponse = new XPayAuthResponseEntityDto()
                .requestId("requestId")
                .urlRedirect("https://example.com");

        /* preconditions */
        Mockito.when(objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID)))
                .thenThrow(new JsonProcessingException("") {
                });
        Mockito.when(xPayInternalApi.authRequestXpay(xPayAuthRequestDto, encodedMdcFields))
                .thenReturn(Mono.just(xPayResponse));

        /* test */
        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNext(xPayResponse)
                .verifyComplete();

        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(1)).authRequestXpay(any(), any());
        verify(postePayInternalApi, times(0)).authRequest(any(), any(), any());
    }

    @Test
    void shouldThrowInvalidRequestWhenCardDetailsAreMissingForCreditCardWithXPay() {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
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
        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectError(InvalidRequestException.class)
                .verify();

        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(0)).authRequestXpay(any(), any());
        verify(postePayInternalApi, times(0)).authRequest(any(), any(), any());
    }
}
