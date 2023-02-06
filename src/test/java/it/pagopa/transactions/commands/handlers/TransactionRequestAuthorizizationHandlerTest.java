package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.cosmos.implementation.BadRequestException;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthResponseEntityDto;
import it.pagopa.generated.transactions.server.model.PostePayAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDto;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class TransactionRequestAuthorizizationHandlerTest {

    @InjectMocks
    private TransactionRequestAuthorizationHandler requestAuthorizationHandler;

    @Mock
    private PaymentGatewayClient paymentGatewayClient;

    @Mock
    private TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;

    @Mock
    private QueueAsyncClient queueAsyncClient;

    private final UUID transactionIdUUID = UUID.randomUUID();

    TransactionId transactionId = new TransactionId(transactionIdUUID);

    @Test
    void shouldSaveAuthorizationEvent() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(new PaymentNotice(paymentToken, rptId, amount, description, nullPaymentContextCode)),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "PPAY",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                null,
                null
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        PostePayAuthResponseEntityDto postePayAuthResponseEntityDto = new PostePayAuthResponseEntityDto()
                .channel("channel")
                .requestId("requestId")
                .urlRedirect("http://example.com");

        ReflectionTestUtils.setField(requestAuthorizationHandler, "queueVisibilityTimeout", "300");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.just(postePayAuthResponseEntityDto));
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.empty());
        Mockito.when(queueAsyncClient.sendMessageWithResponse(BinaryData.fromObject(any()), any(), any()))
                .thenReturn(Mono.empty());

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
    }

    @Test
    void shouldSaveAuthorizationEventXPAY() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(new PaymentNotice(paymentToken, rptId, amount, description, nullPaymentContextCode)),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                "XPAY",
                null
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        XPayAuthResponseEntityDto xPayAuthResponseEntityDto = new XPayAuthResponseEntityDto()
                .requestId("requestId")
                .status("status")
                .urlRedirect("http://example.com");

        ReflectionTestUtils.setField(requestAuthorizationHandler, "queueVisibilityTimeout", "300");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.just(xPayAuthResponseEntityDto));
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.empty());
        Mockito.when(queueAsyncClient.sendMessageWithResponse(BinaryData.fromObject(any()), any(), any()))
                .thenReturn(Mono.empty());

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
    }

    @Test
    void shouldRejectAlreadyProcessedTransaction() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(new PaymentNotice(paymentToken, rptId, amount, description, nullPaymentContextCode)),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "paymentTypeCode",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                null,
                null
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldRejectBadGateway() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(new PaymentNotice(paymentToken, rptId, amount, description, nullPaymentContextCode)),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("VPOS")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                "VPOS",
                new PostePayAuthRequestDetailsDto().detailType("VPOS").accountEmail("test@test.it")
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData)).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData)).thenReturn(Mono.empty());
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof BadRequestException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }
}
