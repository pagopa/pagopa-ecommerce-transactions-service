package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.nodo.v1.dto.AdditionalPaymentInformationsDto;
import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentRequestDto;
import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.documents.*;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionEventCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
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
    NodeForPspClient nodeForPspClient;

    private TransactionId transactionId = new TransactionId(UUID.randomUUID());

    @Test
    void shouldRejectTransactionInWrongState() {
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

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        ClosureSendData closureSendData = new ClosureSendData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(transaction.getRptId(), closureSendData);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldSetTransactionStatusToClosureFailedOnNodoKO() {
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
                TransactionStatusDto.AUTHORIZED
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        ClosureSendData closureSendData = new ClosureSendData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(transaction.getRptId(), closureSendData);

        TransactionClosureSendData transactionClosureSendData = new TransactionClosureSendData(ClosePaymentResponseDto.EsitoEnum.KO, TransactionStatusDto.CLOSURE_FAILED);

        TransactionClosureSentEvent event = new TransactionClosureSentEvent(
                transactionId.toString(),
                transaction.getRptId().toString(),
                transaction.getPaymentToken().toString(),
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
                        transactionId.toString(),
                        rptId.value(),
                        paymentToken.value(),
                        authorizationRequestData
                );

        ClosePaymentRequestDto closePaymentRequest = new ClosePaymentRequestDto()
                .paymentTokens(List.of(transaction.getPaymentToken().value()))
                .outcome(ClosePaymentRequestDto.OutcomeEnum.OK)
                .identificativoPsp(authorizationRequestData.getPspId())
                .tipoVersamento(ClosePaymentRequestDto.TipoVersamentoEnum.fromValue(authorizationRequestData.getPaymentTypeCode()))
                .identificativoIntermediario(authorizationRequestData.getBrokerName())
                .identificativoCanale(authorizationRequestData.getPspChannelCode())
                .pspTransactionId(transaction.getTransactionId().value().toString())
                .totalAmount(new BigDecimal(transaction.getAmount().value() + authorizationRequestData.getFee()))
                .fee(new BigDecimal(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .additionalPaymentInformations(
                        new AdditionalPaymentInformationsDto()
                                .outcomePaymentGateway(updateAuthorizationRequest.getAuthorizationResult().toString())
                                .transactionId(transaction.getTransactionId().value().toString())
                                .authorizationCode(updateAuthorizationRequest.getAuthorizationCode())
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .esito(ClosePaymentResponseDto.EsitoEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));

        Mockito.when(transactionAuthorizationEventStoreRepository.findByTransactionIdAndEventCode(transactionId.value().toString(), TransactionEventCode.TRANSACTION_AUTHORIZATION_REQUESTED_EVENT))
                .thenReturn(Mono.just(transactionAuthorizationRequestedEvent));

        Mockito.when(nodeForPspClient.closePayment(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .expectNextMatches(closureSentEvent -> closureSentEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(argThat(closureSendDataEvent -> closureSendDataEvent.getData().getNewTransactionStatus().equals(TransactionStatusDto.CLOSURE_FAILED)));
    }
}
