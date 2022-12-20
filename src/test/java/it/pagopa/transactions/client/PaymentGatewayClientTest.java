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
import java.util.List;
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
    void shouldNotCallAuthorizationGatewayWithInvalidDetailType() {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new NoticeCode(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description")
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED
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
    }

    @Test
    void shouldReturnAuthorizationResponseForCreditCardWithXPay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new NoticeCode(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description")
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .cvv("345")
                .pan("16589654852")
                .expiryDate(LocalDate.of(2030, Month.DECEMBER, 31))
                .detailType("card")
                .holderName("John Doe");
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getNoticeCodes().stream()
                                        .mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum()
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
    }

    @Test
    void shouldReturnAuthorizationResponseWithPostePay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new NoticeCode(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description")
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getNoticeCodes().stream()
                                        .mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .description(transaction.getNoticeCodes().get(0).transactionDescription().value())
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
        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectNext(postePayResponse)
                .verifyComplete();
    }

    @Test
    void shouldThrowAlreadyProcessedOn401ForCreditCardWithXpay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new NoticeCode(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description")
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .detailType("card")
                .cvv("345")
                .pan("16589654852")
                .expiryDate(LocalDate.of(2030, Month.DECEMBER, 31));
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getNoticeCodes().stream()
                                        .mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum()
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
                                ((AlreadyProcessedException) error).getRptId()
                                        .equals(transaction.getNoticeCodes().get(0).rptId())
                )
                .verify();
    }

    @Test
    void shouldThrowAlreadyProcessedOn401ForPostePay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new NoticeCode(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description")
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getNoticeCodes().stream()
                                        .mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .description(transaction.getNoticeCodes().get(0).transactionDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(paymentTransactionsControllerApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
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
                                ((AlreadyProcessedException) error).getRptId()
                                        .equals(transaction.getNoticeCodes().get(0).rptId())
                )
                .verify();

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();
    }

    @Test
    void shouldThrowGatewayTimeoutOn504() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new NoticeCode(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description")
                        )
                ),
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getNoticeCodes().stream()
                                        .mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .description(transaction.getNoticeCodes().get(0).transactionDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(paymentTransactionsControllerApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
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
    }

    @Test
    void shouldThrowBadGatewayOn500ForPostePay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new NoticeCode(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description")
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getNoticeCodes().stream()
                                        .mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .description(transaction.getNoticeCodes().get(0).transactionDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(transactionIdUUID.toString());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionIdUUID));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(paymentTransactionsControllerApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
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
    }

    @Test
    void shouldThrowBadGatewayOn500ForCreditCardWithXPay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new NoticeCode(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description")
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto().cvv("345").pan("16589654852")
                .expiryDate(LocalDate.of(2030, Month.DECEMBER, 31));
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getNoticeCodes().stream()
                                        .mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum()
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
    }

    @Test
    void fallbackOnEmptyMdcInfoOnMapperErrorForPostePay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new NoticeCode(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description")
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getNoticeCodes().stream()
                                        .mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .description(transaction.getNoticeCodes().get(0).transactionDescription().value())
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
        Mockito.when(paymentTransactionsControllerApi.authRequest(postePayAuthRequest, false, encodedMdcFields))
                .thenReturn(Mono.just(postePayResponse));

        /* test */
        StepVerifier.create(client.requestPostepayAuthorization(authorizationData))
                .expectNext(postePayResponse)
                .verifyComplete();

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

    }

    @Test
    void fallbackOnEmptyMdcInfoOnMapperErrorForCreditCardWithXPay() throws JsonProcessingException {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new NoticeCode(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description")
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto().cvv("345").pan("16589654852")
                .expiryDate(LocalDate.of(2030, Month.DECEMBER, 31));
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
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getNoticeCodes().stream()
                                        .mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum()
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

    }

    @Test
    void shouldThrowInvalidRequestWhenCardDetailsAreMissingForCreditCardWithXPay() {
        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionIdUUID),
                List.of(
                        new NoticeCode(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description")
                        )
                ),
                new Email("foo@example.com"),
                null,
                null,
                TransactionStatusDto.ACTIVATED
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

    }
}
