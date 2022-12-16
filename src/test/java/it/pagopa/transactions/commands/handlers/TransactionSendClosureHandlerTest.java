package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.*;
import it.pagopa.ecommerce.commons.domain.NoticeCode;
import it.pagopa.ecommerce.commons.domain.Transaction;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.pojos.BaseTransactionWithPaymentToken;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.EuroUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    private TransactionId transactionId = new TransactionId(UUID.randomUUID());

    @Test
    void shouldRejectTransactionInWrongState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");

        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");

        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                Arrays.asList(new NoticeCode(paymentToken,
                        rptId,
                        amount,
                        description))
                ,
                email,
                faultCode,
                faultCodeString,
                TransactionStatusDto.AUTHORIZATION_REQUESTED
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        ClosureSendData closureSendData = new ClosureSendData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(transaction.getNoticeCodes().get(0).rptId(), closureSendData);

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                Arrays.asList(new it.pagopa.ecommerce.commons.documents.NoticeCode(
                        paymentToken.value(),
                        rptId.value(),
                        null,
                        null
                )),
                new TransactionActivatedData(
                        email.value(),
                        Arrays.asList(new it.pagopa.ecommerce.commons.documents.NoticeCode(
                                paymentToken.value(),
                                rptId.value(),
                                description.value(),
                                amount.value()
                        )),
                        faultCode,
                        faultCodeString
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                Arrays.asList(new it.pagopa.ecommerce.commons.documents.NoticeCode(
                        paymentToken.value(),
                        rptId.value(),
                        null,
                        null
                )),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationStatusUpdatedEvent(
                transactionId.value().toString(),
                Arrays.asList(new it.pagopa.ecommerce.commons.documents.NoticeCode(
                        paymentToken.value(),
                        rptId.value(),
                        null,
                        null
                )),
                new TransactionAuthorizationStatusUpdateData(
                        AuthorizationResultDto.OK,
                        TransactionStatusDto.AUTHORIZED,
                        "authorizationCode"
                )
        );

        TransactionClosureSentEvent closureSentEvent = new TransactionClosureSentEvent(
                transactionId.value().toString(),
                Arrays.asList(new it.pagopa.ecommerce.commons.documents.NoticeCode(
                        paymentToken.value(),
                        rptId.value(),
                        null,
                        null
                )),
                new TransactionClosureSendData(
                        ClosePaymentResponseDto.OutcomeEnum.OK,
                        TransactionStatusDto.CLOSED
                )
        );

        Flux events = Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationStatusUpdatedEvent,
                closureSentEvent,
                closureSentEvent
        );

        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldSetTransactionStatusToClosureFailedOnNodoKO() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                Arrays.asList(new it.pagopa.ecommerce.commons.documents.NoticeCode(
                        paymentToken.value(),
                        rptId.value(),
                        null,
                        null
                )),
                new TransactionActivatedData(
                        email.value(),
                        Arrays.asList(new it.pagopa.ecommerce.commons.documents.NoticeCode(
                                paymentToken.value(),
                                rptId.value(),
                                description.value(),
                                amount.value()
                        )),
                        faultCode,
                        faultCodeString
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                Arrays.asList(new it.pagopa.ecommerce.commons.documents.NoticeCode(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value()
                )),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationStatusUpdatedEvent(
                transactionId.value().toString(),
                Arrays.asList(new it.pagopa.ecommerce.commons.documents.NoticeCode(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value()
                )),
                new TransactionAuthorizationStatusUpdateData(
                        AuthorizationResultDto.OK,
                        TransactionStatusDto.AUTHORIZED,
                        "authorizationCode"
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent));

        it.pagopa.ecommerce.commons.domain.Transaction transaction = events.reduce(new EmptyTransaction(), Transaction::applyEvent).block();

        TransactionClosureSendData transactionClosureSendData = new TransactionClosureSendData(ClosePaymentResponseDto.OutcomeEnum.KO, TransactionStatusDto.CLOSURE_FAILED);
        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(new RptId(transactionActivatedEvent.getNoticeCodes().get(0).getRptId()), closureSendData);

        TransactionClosureSentEvent event = new TransactionClosureSentEvent(
                transactionId.toString(),
                transactionActivatedEvent.getNoticeCodes().stream().map(noticeCode -> new it.pagopa.ecommerce.commons.documents.NoticeCode(
                        noticeCode.getRptId(),
                        transactionActivatedEvent.getData().getNoticeCodes().stream().filter(noticeCode1 -> noticeCode1.getRptId().equals(noticeCode.getRptId())).findFirst().get().getPaymentToken(),
                        noticeCode.getDescription(),
                        noticeCode.getAmount()
                )).toList(),
                transactionClosureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(List.of(((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData().getNoticeCodes().get(0).getPaymentToken()))
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(EuroUtils.euroCentsToEuro(((BaseTransactionWithPaymentToken) transaction).getNoticeCodes().stream().mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum() + authorizationRequestData.getFee()))
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway", updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code", updateAuthorizationRequest.getAuthorizationCode()
                        )
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);

        /* test */
        StepVerifier.create(
                transactionSendClosureHandler.handle(closureSendCommand)
                )
                .expectNextMatches(closureSentEvent -> closureSentEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(argThat(closureSendDataEvent -> closureSendDataEvent.getData().getNewTransactionStatus().equals(TransactionStatusDto.CLOSURE_FAILED)));
    }
}
