package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionAuthorizeCommand;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class TransactionAuthorizeHandlerTest {

    @InjectMocks
    private TransactionAuthorizeHandler authorizeHandler;

    @Mock
    private PaymentGatewayClient paymentGatewayClient;

    @Mock
    private EcommerceSessionsClient ecommerceSessionsClient;

    @Mock
    private TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;

    @Test
    void shouldSaveAuthorizationEvent() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("rptId");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);

        Transaction transaction = new Transaction(
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
                authorizationRequest.getPspId()
        );

        TransactionAuthorizeCommand authorizeCommand = new TransactionAuthorizeCommand(transaction.getRptId(), authorizationData);

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl("https://example.com");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestAuthorization(authorizationData)).thenReturn(Mono.just(requestAuthorizationResponse));
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.empty());

        /* test */
        authorizeHandler.handle(authorizeCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
    }

    @Test
    void shouldRejectAlreadyAuthorizedTransaction() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("rptId");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);

        Transaction transaction = new Transaction(
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
                authorizationRequest.getPspId()
        );

        TransactionAuthorizeCommand authorizeCommand = new TransactionAuthorizeCommand(transaction.getRptId(), authorizationData);

        /* test */
        StepVerifier.create(authorizeHandler.handle(authorizeCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }
}
