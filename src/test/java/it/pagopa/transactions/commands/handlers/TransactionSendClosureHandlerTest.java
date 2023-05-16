package it.pagopa.transactions.commands.handlers;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.models.SendMessageResult;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData.PaymentGateway;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.PaymentNotice;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.utils.EuroUtils;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.nodo.v2.dto.*;
import it.pagopa.generated.transactions.server.model.OutcomeVposGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static it.pagopa.transactions.commands.handlers.TransactionSendClosureHandler.TIPO_VERSAMENTO_CP;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class TransactionSendClosureHandlerTest {
    private final TransactionsEventStoreRepository<TransactionClosureData> transactionEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private TransactionsEventStoreRepository<TransactionRefundedData> transactionRefundedEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsEventStoreRepository<Void> transactionClosureErrorEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper = Mockito
            .mock(PaymentRequestInfoRedisTemplateWrapper.class);

    private final TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository, "3020");
    private final AuthRequestDataUtils authRequestDataUtils = new AuthRequestDataUtils();
    private final NodeForPspClient nodeForPspClient = Mockito.mock(NodeForPspClient.class);

    private final QueueAsyncClient transactionClosureSentEventQueueClient = Mockito.mock(QueueAsyncClient.class);

    private final QueueAsyncClient refundQueueAsyncClient = Mockito.mock(QueueAsyncClient.class);

    private static final int PAYMENT_TOKEN_VALIDITY = 120;
    private static final int SOFT_TIMEOUT_OFFSET = 10;
    private static final int RETRY_TIMEOUT_INTERVAL = 5;

    private static final String ECOMMERCE_RRN = "rrn";

    private final TransactionSendClosureHandler transactionSendClosureHandler = new TransactionSendClosureHandler(
            transactionEventStoreRepository,
            transactionClosureErrorEventStoreRepository,
            transactionRefundedEventStoreRepository,
            paymentRequestInfoRedisTemplateWrapper,
            nodeForPspClient,
            transactionClosureSentEventQueueClient,
            PAYMENT_TOKEN_VALIDITY,
            SOFT_TIMEOUT_OFFSET,
            RETRY_TIMEOUT_INTERVAL,
            refundQueueAsyncClient,
            transactionsUtils,
            authRequestDataUtils
    );

    private final TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);

    private static MockedStatic<OffsetDateTime> offsetDateTimeMockedStatic;

    private static final String expectedOperationTimestamp = "2023-01-01T01:02:03";
    private static final OffsetDateTime operationTimestamp = OffsetDateTime
            .parse(expectedOperationTimestamp.concat("+01:00"));

    @BeforeAll
    static void init() {
        offsetDateTimeMockedStatic = Mockito.mockStatic(OffsetDateTime.class);
        offsetDateTimeMockedStatic.when(OffsetDateTime::now).thenReturn(operationTimestamp);
    }

    @AfterAll
    static void shutdown() {
        offsetDateTimeMockedStatic.close();
    }

    @Test
    void shouldRejectTransactionInWrongState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");

        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation("77777777777", false, 100, null))
                )
        );

        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                PaymentNotices.stream().map(
                        paymentNotice -> new PaymentNotice(
                                new PaymentToken(paymentNotice.getPaymentToken()),
                                new RptId(paymentNotice.getRptId()),
                                new TransactionAmount(paymentNotice.getAmount()),
                                new TransactionDescription(paymentNotice.getDescription()),
                                new PaymentContextCode(paymentNotice.getPaymentContextCode()),
                                List.of(
                                        new PaymentTransferInfo(
                                                paymentNotice.getRptId().substring(0, 11),
                                                false,
                                                100,
                                                null
                                        )
                                )
                        )
                )
                        .toList(),
                email,
                faultCode,
                faultCodeString,
                Transaction.ClientId.CHECKOUT,
                idCart
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        ClosureSendData closureSendData = new ClosureSendData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                closureSendData
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value(),
                new TransactionActivatedData(
                        email,
                        transaction.getTransactionActivatedData().getPaymentNotices(),
                        faultCode,
                        faultCodeString,
                        it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
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
                        "authorizationRequestId",
                        PaymentGateway.VPOS,
                        null
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        "rrn",
                        expectedOperationTimestamp,
                        AuthorizationResultDto.OK
                )
        );

        TransactionClosedEvent closedEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        Flux events = Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closedEvent
        );

        Mockito.when(eventStoreRepository.findByTransactionId(any())).thenReturn(events);

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
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
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
                        "authorizationRequestId",
                        PaymentGateway.XPAY,
                        null
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        null,
                        null,
                        expectedOperationTimestamp,
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.KO)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.KO);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Rifiutato")
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isRight());
                    assertNotNull(next.get());
                    assertEquals(event.getData().getResponseOutcome(), next.get().getData().getResponseOutcome());
                    assertEquals(event.getEventCode(), next.get().getEventCode());
                    assertEquals(event.getTransactionId(), next.get().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
    }

    @Test
    void shoulGenerateClosureFailedEventOnAuthorizationKO() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
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
                        "authorizationRequestId",
                        PaymentGateway.XPAY,
                        null
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        null,
                        null,
                        expectedOperationTimestamp,
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.KO)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.OK);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Rifiutato")
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.OK);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isRight());
                    assertNotNull(next.get());
                    assertEquals(event.getData().getResponseOutcome(), next.get().getData().getResponseOutcome());
                    assertEquals(event.getEventCode(), next.get().getEventCode());
                    assertEquals(event.getTransactionId(), next.get().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
    }

    @Test
    void shoulGenerateClosedEventOnAuthorizationOKAndOkNodoClosePayment() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation("77777777777", false, 100, null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
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
                        "authorizationRequestId",
                        PaymentGateway.VPOS,
                        URI.create("test/logo")
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        ECOMMERCE_RRN,
                        expectedOperationTimestamp,
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                                .rrn(ECOMMERCE_RRN)
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosedEvent event = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                        + authorizationRequestData.getFee()
        );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .totalAmount(totalAmount)
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        new AdditionalPaymentInformationsDto()
                                .outcomePaymentGateway(
                                        AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.fromValue(
                                                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                        .getOutcome().toString()
                                        )
                                )
                                .authorizationCode(
                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                .getAuthorizationCode()
                                )
                                .fee(
                                        EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).setScale(2)
                                                .toPlainString()
                                )
                                .timestampOperation(
                                        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                )
                                .rrn(((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getRrn())
                                .totalAmount(totalAmount.setScale(2).toPlainString())
                )
                .transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Autorizzato")
                                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                                                .amount(
                                                        EuroUtils.euroCentsToEuro(
                                                                ((BaseTransactionWithPaymentToken) transaction)
                                                                        .getPaymentNotices().stream()
                                                                        .mapToInt(
                                                                                PaymentNotice -> PaymentNotice
                                                                                        .transactionAmount().value()
                                                                        ).sum()
                                                        )
                                                )
                                                .grandTotal(
                                                        EuroUtils.euroCentsToEuro(
                                                                ((BaseTransactionWithPaymentToken) transaction)
                                                                        .getPaymentNotices().stream()
                                                                        .mapToInt(
                                                                                PaymentNotice -> PaymentNotice
                                                                                        .transactionAmount().value()
                                                                        ).sum()
                                                                        + authorizationRequestData.getFee()
                                                        )
                                                )
                                                .rrn(
                                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getRrn()
                                                )
                                                .authorizationCode(
                                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getAuthorizationCode()
                                                )
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                                .psp(
                                                        new PspDto()
                                                                .idPsp(
                                                                        authorizationRequestData
                                                                                .getPspId()
                                                                )
                                                                .idChannel(
                                                                        authorizationRequestData
                                                                                .getPspChannelCode()
                                                                )
                                                                .businessName(
                                                                        authorizationRequestData
                                                                                .getPspBusinessName()
                                                                )
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                                .brandLogo(
                                                        authorizationRequestData.getLogo()
                                                                .getPath()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.OK);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isRight());
                    assertNotNull(next.get());
                    assertEquals(event.getData().getResponseOutcome(), next.get().getData().getResponseOutcome());
                    assertEquals(event.getEventCode(), next.get().getEventCode());
                    assertEquals(event.getTransactionId(), next.get().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
    }

    @Test
    void shouldEnqueueErrorEventOnGenericNodoFailure() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
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
                        "authorizationRequestId",
                        PaymentGateway.XPAY,
                        URI.create("test/logo")
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        null,
                        expectedOperationTimestamp,
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.OK);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                        .sum()
                        + authorizationRequestData.getFee())
        );

        BigDecimal fee = EuroUtils.euroCentsToEuro(authorizationRequestData.getFee());

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .totalAmount(totalAmount)
                .fee(fee)
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        new AdditionalPaymentInformationsDto()
                                .outcomePaymentGateway(
                                        AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.fromValue(
                                                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                        .getOutcome().toString()
                                        )
                                )
                                .authorizationCode(
                                        ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                .getAuthorizationCode()
                                )
                                .fee(fee.toString())
                                .timestampOperation(
                                        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                )
                                .totalAmount(totalAmount.toString())
                ).transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Autorizzato")
                                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                                                .amount(
                                                        EuroUtils.euroCentsToEuro(
                                                                ((BaseTransactionWithPaymentToken) transaction)
                                                                        .getPaymentNotices().stream()
                                                                        .mapToInt(
                                                                                PaymentNotice -> PaymentNotice
                                                                                        .transactionAmount().value()
                                                                        ).sum()
                                                        )
                                                )
                                                .grandTotal(
                                                        totalAmount
                                                )
                                                .authorizationCode(
                                                        ((OutcomeXpayGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getAuthorizationCode()
                                                )
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                                .psp(
                                                        new PspDto()
                                                                .idPsp(
                                                                        authorizationRequestData
                                                                                .getPspId()
                                                                )
                                                                .idChannel(
                                                                        authorizationRequestData
                                                                                .getPspChannelCode()
                                                                )
                                                                .businessName(
                                                                        authorizationRequestData
                                                                                .getPspBusinessName()
                                                                )
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                                .brandLogo(
                                                        authorizationRequestData.getLogo()
                                                                .getPath()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))
                );

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value()
        );

        RuntimeException closePaymentError = new RuntimeException("Network error");

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(argThat(e -> e.getEventCode().equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT)));
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
    }

    @Test
    void shouldEnqueueErrorEventOnRedisFailure() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
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
                        "authorizationRequestId",
                        PaymentGateway.VPOS,
                        URI.create("logo/test")
                )
        );

        TransactionAuthorizationCompletedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        ECOMMERCE_RRN,
                        expectedOperationTimestamp,
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                                .rrn(ECOMMERCE_RRN)
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                        .sum()
                        + authorizationRequestData.getFee())
        );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .totalAmount(
                        totalAmount
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        new AdditionalPaymentInformationsDto()
                                .outcomePaymentGateway(
                                        AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.fromValue(
                                                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                        .getOutcome().toString()
                                        )
                                )
                                .authorizationCode(
                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                .getAuthorizationCode()
                                )
                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString())
                                .timestampOperation(
                                        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                )
                                .rrn(ECOMMERCE_RRN)
                                .totalAmount(totalAmount.toString())
                ).transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Autorizzato")
                                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                                                .amount(
                                                        EuroUtils.euroCentsToEuro(
                                                                ((BaseTransactionWithPaymentToken) transaction)
                                                                        .getPaymentNotices().stream()
                                                                        .mapToInt(
                                                                                PaymentNotice -> PaymentNotice
                                                                                        .transactionAmount().value()
                                                                        ).sum()
                                                        )
                                                )
                                                .grandTotal(
                                                        EuroUtils.euroCentsToEuro(
                                                                ((BaseTransactionWithPaymentToken) transaction)
                                                                        .getPaymentNotices().stream()
                                                                        .mapToInt(
                                                                                PaymentNotice -> PaymentNotice
                                                                                        .transactionAmount().value()
                                                                        ).sum()
                                                                        + authorizationRequestData.getFee()
                                                        )
                                                )
                                                .rrn(
                                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getRrn()
                                                )
                                                .authorizationCode(
                                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getAuthorizationCode()
                                                )
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                                .psp(
                                                        new PspDto()
                                                                .idPsp(
                                                                        authorizationRequestData
                                                                                .getPspId()
                                                                )
                                                                .idChannel(
                                                                        authorizationRequestData
                                                                                .getPspChannelCode()
                                                                )
                                                                .businessName(
                                                                        authorizationRequestData
                                                                                .getPspBusinessName()
                                                                )
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                                .brandLogo(
                                                        authorizationRequestData.getLogo()
                                                                .getPath()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value()
        );

        RuntimeException redisError = new RuntimeException("Network error");

        /* preconditions */
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);
        // first call to redis is ko, second one is ok
        Mockito.when(transactionEventStoreRepository.save(any()))
                .thenReturn(Mono.error(redisError));
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any()))
                .thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(argThat(e -> e.getEventCode().equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT)));

        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
    }

    @Test
    void shouldNotEnqueueErrorEventOnNodoUnrecoverableFailureTransactionAuthorized() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
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
                        "authorizationRequestId",
                        PaymentGateway.XPAY,
                        URI.create("logo/test")
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        null,
                        expectedOperationTimestamp,
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                        .sum()
                        + authorizationRequestData.getFee())
        );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                                        .sum()
                                        + authorizationRequestData.getFee())
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        new AdditionalPaymentInformationsDto()
                                .outcomePaymentGateway(
                                        AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.fromValue(
                                                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                        .getOutcome().toString()
                                        )
                                )
                                .authorizationCode(
                                        ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                .getAuthorizationCode()
                                )
                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString())
                                .timestampOperation(
                                        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                )
                                .totalAmount(totalAmount.toString())
                ).transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Autorizzato")
                                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                                                .amount(
                                                        EuroUtils.euroCentsToEuro(
                                                                ((BaseTransactionWithPaymentToken) transaction)
                                                                        .getPaymentNotices().stream()
                                                                        .mapToInt(
                                                                                PaymentNotice -> PaymentNotice
                                                                                        .transactionAmount().value()
                                                                        ).sum()
                                                        )
                                                )
                                                .grandTotal(
                                                        totalAmount
                                                )
                                                .authorizationCode(
                                                        ((OutcomeXpayGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getAuthorizationCode()
                                                )
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                                .psp(
                                                        new PspDto()
                                                                .idPsp(
                                                                        authorizationRequestData
                                                                                .getPspId()
                                                                )
                                                                .idChannel(
                                                                        authorizationRequestData
                                                                                .getPspChannelCode()
                                                                )
                                                                .businessName(
                                                                        authorizationRequestData
                                                                                .getPspBusinessName()
                                                                )
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                                .brandLogo(
                                                        authorizationRequestData.getLogo()
                                                                .getPath()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );
        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value()
        );

        RuntimeException closePaymentError = new BadGatewayException("Bad request error", HttpStatus.BAD_REQUEST);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(
                refundQueueAsyncClient.sendMessageWithResponse(any(BinaryData.class), any(), any())

        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(
                refundQueueAsyncClient,
                Mockito.times(1)
        ).sendMessageWithResponse(
                argThat(
                        (BinaryData b) -> b.toObject(TransactionRefundRequestedEvent.class).getData()
                                .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSURE_ERROR)
                ),
                any(),
                any()
        );
        // check that one closure error event is saved and none is sent to event
        // dispatcher
        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(any());
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(0))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
    }

    @Test
    void shouldNotEnqueueErrorEventOnNodoUnrecoverableFailureTransactionNotAuthorized() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
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
                        "authorizationRequestId",
                        PaymentGateway.VPOS,
                        null
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        null,
                        null,
                        expectedOperationTimestamp,
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.KO)
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Rifiutato")
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestedEvent.getData()
                                                                .getPaymentTypeCode()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value()
        );

        RuntimeException closePaymentError = new BadGatewayException("Bad request error", HttpStatus.BAD_REQUEST);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(
                refundQueueAsyncClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(
                refundQueueAsyncClient,
                Mockito.times(1)
        ).sendMessageWithResponse(
                argThat(
                        (BinaryData b) -> b.toObject(TransactionRefundRequestedEvent.class).getData()
                                .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSURE_ERROR)
                ),
                any(),
                any()
        );
        // check that no closure error event is saved but not sent to event dispatcher
        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(any());
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(0))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
        // check that closure error event is saved
        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1)).save(any());
    }

    @Test
    void shouldNotEnqueueErrorEventOnNodoRecoverableFailureTransactionNotAuthorized() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
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
                        "authorizationRequestId",
                        PaymentGateway.XPAY,
                        URI.create("logo/test")
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        null,
                        null,
                        expectedOperationTimestamp,
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.KO)
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.KO);

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Rifiutato")
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestedEvent.getData()
                                                                .getPaymentTypeCode()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value()
        );

        RuntimeException closePaymentError = new BadGatewayException(
                "Internal server error",
                HttpStatus.INTERNAL_SERVER_ERROR
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        // check that no closure error event is saved and sent to event dispatcher
        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(argThat(e -> e.getEventCode().equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT)));
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
        // check that closure event with KO status is not saved
        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT
                                .equals(eventArg.getEventCode())
                                && eventArg.getData().getResponseOutcome().equals(TransactionClosureData.Outcome.KO)
                )
        );
    }

    @Test
    void shouldEnqueueErrorEventOnNodoRecoverableFailureTransactionAuthorized() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
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
                        "authorizationRequestId",
                        PaymentGateway.VPOS,
                        URI.create("logo/test")
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        ECOMMERCE_RRN,
                        expectedOperationTimestamp,
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                                .rrn(ECOMMERCE_RRN)
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.KO);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                        .sum()
                        + authorizationRequestData.getFee())
        );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                                        .sum()
                                        + authorizationRequestData.getFee())
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        new AdditionalPaymentInformationsDto()
                                .outcomePaymentGateway(
                                        AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.fromValue(
                                                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                        .getOutcome().toString()
                                        )
                                )
                                .authorizationCode(
                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                .getAuthorizationCode()
                                )
                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString())
                                .timestampOperation(
                                        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                )
                                .rrn(ECOMMERCE_RRN)
                                .totalAmount(totalAmount.toString())

                ).transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Autorizzato")
                                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                                                .amount(
                                                        EuroUtils.euroCentsToEuro(
                                                                ((BaseTransactionWithPaymentToken) transaction)
                                                                        .getPaymentNotices().stream()
                                                                        .mapToInt(
                                                                                PaymentNotice -> PaymentNotice
                                                                                        .transactionAmount().value()
                                                                        ).sum()
                                                        )
                                                )
                                                .grandTotal(
                                                        EuroUtils.euroCentsToEuro(
                                                                ((BaseTransactionWithPaymentToken) transaction)
                                                                        .getPaymentNotices().stream()
                                                                        .mapToInt(
                                                                                PaymentNotice -> PaymentNotice
                                                                                        .transactionAmount().value()
                                                                        ).sum()
                                                                        + authorizationRequestData.getFee()
                                                        )
                                                )
                                                .rrn(
                                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getRrn()
                                                )
                                                .authorizationCode(
                                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getAuthorizationCode()
                                                )
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                                .psp(
                                                        new PspDto()
                                                                .idPsp(
                                                                        authorizationRequestData
                                                                                .getPspId()
                                                                )
                                                                .idChannel(
                                                                        authorizationRequestData
                                                                                .getPspChannelCode()
                                                                )
                                                                .businessName(
                                                                        authorizationRequestData
                                                                                .getPspBusinessName()
                                                                )
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                                .brandLogo(
                                                        authorizationRequestData.getLogo()
                                                                .getPath()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value()
        );

        RuntimeException closePaymentError = new BadGatewayException(
                "Internal server error",
                HttpStatus.INTERNAL_SERVER_ERROR
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(argThat(e -> e.getEventCode().equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT)));
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
    }

    @Test
    void shouldEnqueueErrorEventOnNodoRecoverableFailure() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> paymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value(),
                new TransactionActivatedData(
                        email,
                        paymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
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
                        "authorizationRequestId",
                        PaymentGateway.XPAY,
                        URI.create("logo/test")
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        null,
                        expectedOperationTimestamp,
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.OK);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                        .sum()
                        + authorizationRequestData.getFee())
        );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .totalAmount(
                        totalAmount
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        new AdditionalPaymentInformationsDto()
                                .outcomePaymentGateway(
                                        AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.fromValue(
                                                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                        .getOutcome().toString()
                                        )
                                )
                                .authorizationCode(
                                        ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                .getAuthorizationCode()
                                )
                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString())
                                .timestampOperation(
                                        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                )
                                .totalAmount(totalAmount.toString())
                )
                .transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Autorizzato")
                                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                                                .amount(
                                                        EuroUtils.euroCentsToEuro(
                                                                (transactionActivatedEvent.getData().getPaymentNotices()
                                                                        .stream()
                                                                        .mapToInt(
                                                                                it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount
                                                                        )
                                                                        .sum())
                                                        )
                                                )
                                                .grandTotal(
                                                        EuroUtils.euroCentsToEuro(
                                                                (transactionActivatedEvent.getData().getPaymentNotices()
                                                                        .stream()
                                                                        .mapToInt(
                                                                                it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount
                                                                        )
                                                                        .sum()
                                                                        + authorizationRequestData.getFee())
                                                        )
                                                )
                                                .rrn(authorizationCompletedEvent.getData().getRrn())
                                                .authorizationCode(
                                                        ((OutcomeXpayGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway())
                                                                        .getAuthorizationCode()
                                                )
                                                .creationDate(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getCreationDate().toOffsetDateTime()
                                                )
                                                .psp(
                                                        new PspDto()
                                                                .idPsp(
                                                                        authorizationRequestData
                                                                                .getPspId()
                                                                )
                                                                .idChannel(
                                                                        authorizationRequestData
                                                                                .getPspChannelCode()
                                                                )
                                                                .businessName(
                                                                        authorizationRequestData
                                                                                .getPspBusinessName()
                                                                )
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                                .brandLogo(
                                                        authorizationRequestData.getLogo()
                                                                .getPath()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value()
        );

        RuntimeException closePaymentError = new BadGatewayException(
                "Internal server error",
                HttpStatus.INTERNAL_SERVER_ERROR
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(argThat(e -> e.getEventCode().equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT)));
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
    }

    @Test
    void shouldSendRefundRequestEventOnQueueForAuthorizedTransactionAndNodoClosePaymentResponseOutcomeKO() {

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(AuthorizationResultDto.OK);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                                .rrn(ECOMMERCE_RRN)
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosedEvent event = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.KO);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();
        BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                        .sum()
                        + authorizationRequestData.getFee())
        );
        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .totalAmount(
                        totalAmount
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        new AdditionalPaymentInformationsDto()
                                .outcomePaymentGateway(
                                        AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.fromValue(
                                                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                        .getOutcome().toString()
                                        )
                                )
                                .authorizationCode(
                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                .getAuthorizationCode()
                                )
                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString())
                                .timestampOperation(
                                        OffsetDateTime.now()
                                                .truncatedTo(ChronoUnit.SECONDS)
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                )
                                .rrn(ECOMMERCE_RRN)
                                .totalAmount(totalAmount.toString())
                ).transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Autorizzato")
                                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                                                .amount(
                                                        EuroUtils.euroCentsToEuro(
                                                                ((BaseTransactionWithPaymentToken) transaction)
                                                                        .getPaymentNotices().stream()
                                                                        .mapToInt(
                                                                                PaymentNotice -> PaymentNotice
                                                                                        .transactionAmount().value()
                                                                        ).sum()
                                                        )
                                                )
                                                .grandTotal(
                                                        totalAmount
                                                )
                                                .rrn(
                                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getRrn()
                                                )
                                                .authorizationCode(
                                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getAuthorizationCode()
                                                )
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                                .psp(
                                                        new PspDto()
                                                                .idPsp(
                                                                        authorizationRequestData
                                                                                .getPspId()
                                                                )
                                                                .idChannel(
                                                                        authorizationRequestData
                                                                                .getPspChannelCode()
                                                                )
                                                                .businessName(
                                                                        authorizationRequestData
                                                                                .getPspBusinessName()
                                                                )
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                                .brandLogo(
                                                        authorizationRequestData.getLogo()
                                                                .toString()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(
                refundQueueAsyncClient.sendMessageWithResponse(any(BinaryData.class), any(), any())

        ).thenReturn(queueSuccessfulResponse());

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isRight());
                    assertNotNull(next.get());
                    assertEquals(event.getData().getResponseOutcome(), next.get().getData().getResponseOutcome());
                    assertEquals(event.getEventCode(), next.get().getEventCode());
                    assertEquals(event.getTransactionId(), next.get().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(refundQueueAsyncClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> {
                                    TransactionRefundRequestedEvent e = b
                                            .toObject(TransactionRefundRequestedEvent.class);
                                    return e.getTransactionId().equals(transactionId.value()) && e.getData()
                                            .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSED);
                                }

                        ),
                        eq(Duration.ZERO),
                        isNull()
                );
    }

    @Test
    void shouldSendRefundRequestedEventOnQueueForAuthorizedTransactionAndNodoClosePaymentUnrecoverableError() {

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(AuthorizationResultDto.OK);
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                                .rrn(ECOMMERCE_RRN)
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();
        BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                        .sum()
                        + authorizationRequestData.getFee())
        );
        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .totalAmount(
                        totalAmount
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        new AdditionalPaymentInformationsDto()
                                .outcomePaymentGateway(
                                        AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.fromValue(
                                                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                        .getOutcome().toString()
                                        )
                                )
                                .authorizationCode(
                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                                .getAuthorizationCode()
                                )
                                .fee(
                                        EuroUtils.euroCentsToEuro(authorizationRequestData.getFee())
                                                .toString()
                                )
                                .timestampOperation(
                                        OffsetDateTime.now()
                                                .truncatedTo(ChronoUnit.SECONDS)
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                )
                                .rrn(ECOMMERCE_RRN)
                                .totalAmount(totalAmount.toString())
                ).transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Autorizzato")
                                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                                                .amount(
                                                        EuroUtils.euroCentsToEuro(
                                                                ((BaseTransactionWithPaymentToken) transaction)
                                                                        .getPaymentNotices().stream()
                                                                        .mapToInt(
                                                                                PaymentNotice -> PaymentNotice
                                                                                        .transactionAmount().value()
                                                                        ).sum()
                                                        )
                                                )
                                                .grandTotal(
                                                        EuroUtils.euroCentsToEuro(
                                                                ((BaseTransactionWithPaymentToken) transaction)
                                                                        .getPaymentNotices().stream()
                                                                        .mapToInt(
                                                                                PaymentNotice -> PaymentNotice
                                                                                        .transactionAmount().value()
                                                                        ).sum()
                                                                        + authorizationRequestData.getFee()
                                                        )
                                                )
                                                .rrn(
                                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getRrn()
                                                )
                                                .authorizationCode(
                                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getAuthorizationCode()
                                                )
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                                .psp(
                                                        new PspDto()
                                                                .idPsp(
                                                                        authorizationRequestData
                                                                                .getPspId()
                                                                )
                                                                .idChannel(
                                                                        authorizationRequestData
                                                                                .getPspChannelCode()
                                                                )
                                                                .businessName(
                                                                        authorizationRequestData
                                                                                .getPspBusinessName()
                                                                )
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                                .brandLogo(
                                                        authorizationRequestData.getLogo()
                                                                .toString()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        RuntimeException closePaymentError = new BadGatewayException("Bad request error", HttpStatus.BAD_REQUEST);

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value()
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);
        Mockito.when(
                refundQueueAsyncClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any()))
                .thenAnswer(a -> Mono.just(a.getArgument(0)));

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        /*
         * check that the closure event with outcome KO is sent in the transaction
         * activated queue
         */
        Mockito.verify(refundQueueAsyncClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> {
                                    TransactionRefundRequestedEvent e = b
                                            .toObject(TransactionRefundRequestedEvent.class);
                                    return e.getTransactionId().equals(transactionId.value()) && e.getData()
                                            .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSURE_ERROR);
                                }
                        ),
                        eq(Duration.ZERO),
                        isNull()
                );

        /*
         * check that no event is sent on the closure error queue
         */
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(0))
                .sendMessageWithResponse(
                        any(BinaryData.class),
                        any(),
                        isNull()
                );
    }

    @Test
    void shouldNotSendClosedEventOnQueueForNotAuthorizedTransactionAndNodoClosePaymentResponseOutcomeKO() {

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(AuthorizationResultDto.KO);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.KO)
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.KO);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Rifiutato")
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isRight());
                    assertNotNull(next.get());
                    assertEquals(event.getData().getResponseOutcome(), next.get().getData().getResponseOutcome());
                    assertEquals(event.getEventCode(), next.get().getEventCode());
                    assertEquals(event.getTransactionId(), next.get().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(0))
                .sendMessageWithResponse(
                        any(BinaryData.class),
                        any(),
                        any()
                );
    }

    @Test
    void shouldNotSendClosedEventOnQueueForNotAuthorizedTransactionAndNodoClosePaymentUnrecoverableError() {

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        AuthorizationResultDto.KO
                );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.KO)
                )
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        transactionId.value()
                                                )
                                                .transactionStatus("Rifiutato")
                                                .creationDate(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getCreationDate().toOffsetDateTime()
                                                )
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value()
        );

        RuntimeException closePaymentError = new BadGatewayException("Bad request error", HttpStatus.BAD_REQUEST);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value())).thenReturn(events);
        Mockito.when(
                refundQueueAsyncClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any()))
                .thenAnswer(a -> Mono.just(a.getArgument(0)));

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(
                refundQueueAsyncClient,
                Mockito.times(1)
        ).sendMessageWithResponse(
                argThat(
                        (BinaryData b) -> b.toObject(TransactionRefundRequestedEvent.class).getData()
                                .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSURE_ERROR)
                ),
                any(),
                any()
        );
        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(0))
                .sendMessageWithResponse(
                        any(BinaryData.class),
                        any(),
                        any()
                );
    }

    private static Mono<Response<SendMessageResult>> queueSuccessfulResponse() {
        return Mono.just(new Response<>() {
            @Override
            public int getStatusCode() {
                return 200;
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }

            @Override
            public HttpRequest getRequest() {
                return null;
            }

            @Override
            public SendMessageResult getValue() {
                return new SendMessageResult();
            }
        });
    }
}
