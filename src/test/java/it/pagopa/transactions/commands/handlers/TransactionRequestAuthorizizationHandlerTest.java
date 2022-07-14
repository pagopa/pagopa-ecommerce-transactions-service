package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestData;
import it.pagopa.transactions.domain.*;
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

import java.util.UUID;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;

import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class TransactionRequestAuthorizizationHandlerTest {

    @InjectMocks
    private TransactionRequestAuthorizationHandler requestAuthorizationHandler;

    @Mock
    private PaymentGatewayClient paymentGatewayClient;

    @Mock
    private EcommerceSessionsClient ecommerceSessionsClient;

    @Mock
    private TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;
   
    @Mock
    private QueueAsyncClient queueAsyncClient;

    private UUID transactionIdUUID = UUID.randomUUID();

    TransactionId transactionId = new TransactionId(transactionIdUUID);

    @Test
    void shouldSaveAuthorizationEvent() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("rptId");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);

        Transaction transaction = new Transaction(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                TransactionStatusDto.INITIALIZED
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
                "pspChannelCode"
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(transaction.getRptId(), authorizationData);

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl("https://example.com");

        ReflectionTestUtils.setField(requestAuthorizationHandler, "queueVisibilityTimeout", "300");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestAuthorization(authorizationData)).thenReturn(Mono.just(requestAuthorizationResponse));
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.empty());
        Mockito.when(queueAsyncClient.sendMessageWithResponse(BinaryData.fromObject(any()),any(),any())).thenReturn(Mono.empty());

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
    }

    @Test
    void shouldRejectAlreadyProcessedTransaction() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("rptId");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);

        Transaction transaction = new Transaction(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                TransactionStatusDto.AUTHORIZATION_REQUESTED
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
                "pspChannelCode"
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(transaction.getRptId(), authorizationData);

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }
}
