package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.nodo.v1.dto.AdditionalPaymentInformationsDto;
import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentRequestDto;
import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentResponseDto;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NotificationsServiceClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.documents.*;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.domain.TransactionInitialized;
import it.pagopa.transactions.domain.pojos.BaseTransaction;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionEventCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionSendClosureHandlerTest {

    @InjectMocks
    private TransactionSendClosureHandler transactionSendClosureHandler;

    @Mock
    private TransactionsEventStoreRepository<TransactionClosureSendData> transactionEventStoreRepository;

    @Mock
    private TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionAuthorizationEventStoreRepository;

    @Mock
    private TransactionsEventStoreRepository<Object> eventStoreRepository;

    @Mock
    NodeForPspClient nodeForPspClient;

    @Mock
    NotificationsServiceClient notificationsServiceClient;

    private TransactionId transactionId = new TransactionId(UUID.randomUUID());

    @Test
    void shouldSetTransactionStatusToClosureFailedOnNodoKO() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("rptId");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");

        TransactionInitEvent transactionInitEvent = new TransactionInitEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionInitData(
                        description.value(),
                        amount.value(),
                        email.value(),
                        "faultCode",
                        "faultCodeString"
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationStatusUpdatedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionAuthorizationStatusUpdateData(
                        AuthorizationResultDto.OK,
                        TransactionStatusDto.AUTHORIZED
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        TransactionClosureSendData transactionClosureSendData = new TransactionClosureSendData(ClosePaymentResponseDto.EsitoEnum.KO, TransactionStatusDto.CLOSURE_FAILED);

        TransactionClosureSentEvent event = new TransactionClosureSentEvent(
                transactionId.value().toString(),
                transactionInitEvent.getRptId(),
                transactionInitEvent.getPaymentToken(),
                transactionClosureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = new TransactionAuthorizationRequestData(
                amount.value(),
                1,
                "paymentInstrumentId",
                "pspId",
                ClosePaymentRequestDto.TipoVersamentoEnum.BP.toString(),
                "brokerName",
                "pspChannelCode"
        );
        TransactionAuthorizationRequestedEvent transactionAuthorizationRequestedEvent =
                new TransactionAuthorizationRequestedEvent(
                        transactionId.value().toString(),
                        rptId.value(),
                        paymentToken.value(),
                        authorizationRequestData
                );

        ClosePaymentRequestDto closePaymentRequest = new ClosePaymentRequestDto()
                .paymentTokens(List.of(transactionInitEvent.getPaymentToken()))
                .outcome(ClosePaymentRequestDto.OutcomeEnum.OK)
                .identificativoPsp(authorizationRequestData.getPspId())
                .tipoVersamento(ClosePaymentRequestDto.TipoVersamentoEnum.fromValue(authorizationRequestData.getPaymentTypeCode()))
                .identificativoIntermediario(authorizationRequestData.getBrokerName())
                .identificativoCanale(authorizationRequestData.getPspChannelCode())
                .pspTransactionId(transactionInitEvent.getTransactionId())
                .totalAmount(new BigDecimal(transactionInitEvent.getData().getAmount() + authorizationRequestData.getFee()))
                .fee(new BigDecimal(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .additionalPaymentInformations(
                        new AdditionalPaymentInformationsDto()
                                .outcomePaymentGateway(updateAuthorizationRequest.getAuthorizationResult().toString())
                                .transactionId(transactionInitEvent.getTransactionId())
                                .authorizationCode(updateAuthorizationRequest.getAuthorizationCode())
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .esito(ClosePaymentResponseDto.EsitoEnum.KO);

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(transactionInitEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent));

        it.pagopa.transactions.domain.Transaction transaction = events.reduce(((Transaction) new EmptyTransaction()), (t, e) -> t.applyEvent(e)).block();

        ClosureSendData closureSendData = new ClosureSendData(
                ((BaseTransaction) transaction),
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(new RptId(transactionInitEvent.getRptId()), closureSendData);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));

        Mockito.when(transactionAuthorizationEventStoreRepository.findByTransactionIdAndEventCode(transactionId.value().toString(), TransactionEventCode.TRANSACTION_AUTHORIZATION_REQUESTED_EVENT))
                .thenReturn(Mono.just(transactionAuthorizationRequestedEvent));

        Mockito.when(nodeForPspClient.closePayment(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);

        Mockito.when(notificationsServiceClient.sendSuccessEmail(any())).thenReturn(Mono.just(new NotificationEmailResponseDto().outcome("OK")));

        /* test */
        StepVerifier.create(
                transactionSendClosureHandler.handle(closureSendCommand)
                )
                .expectNextMatches(closureSentEvent -> closureSentEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(argThat(closureSendDataEvent -> closureSendDataEvent.getData().getNewTransactionStatus().equals(TransactionStatusDto.CLOSURE_FAILED)));
    }
}
