package it.pagopa.transactions.commands.handlers.v1;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.storage.queue.models.SendMessageResult;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData.PaymentGateway;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.queues.TracingUtilsTests;
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
import it.pagopa.transactions.utils.UUIDUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class TransactionSendClosureHandlerTest {
    public static final String AUTHORIZATION_CODE = "authorizationCode";
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

    private final UUIDUtils uuidUtils = new UUIDUtils();
    private final AuthRequestDataUtils authRequestDataUtils = new AuthRequestDataUtils(uuidUtils);
    private final NodeForPspClient nodeForPspClient = Mockito.mock(NodeForPspClient.class);

    private final QueueAsyncClient transactionClosureSentEventQueueClient = Mockito.mock(QueueAsyncClient.class);

    private final QueueAsyncClient refundQueueAsyncClient = Mockito.mock(QueueAsyncClient.class);

    private static final int PAYMENT_TOKEN_VALIDITY = 120;
    private static final int SOFT_TIMEOUT_OFFSET = 10;
    private static final int RETRY_TIMEOUT_INTERVAL = 5;

    private final int transientQueueEventsTtlSeconds = 30;

    private final TracingUtils tracingUtils = TracingUtilsTests.getMock();

    @Captor
    private ArgumentCaptor<Duration> durationArgumentCaptor;

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
            authRequestDataUtils,
            transientQueueEventsTtlSeconds,
            tracingUtils
    );

    private final TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);
    private final String ECOMMERCE_RRN = uuidUtils.uuidToBase64(transactionId.uuid());

    private static final String expectedOperationTimestamp = "2023-01-01T01:02:03";
    private static final OffsetDateTime operationTimestamp = OffsetDateTime
            .parse(expectedOperationTimestamp.concat("+01:00"));

    @Test
    void shouldRejectTransactionInWrongState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");

        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> paymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation("77777777777", false, 100, null)),
                        false
                )
        );

        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentNotices.stream().map(
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
                                ),
                                paymentNotice.isAllCCP()
                        )
                )
                        .toList(),
                email,
                faultCode,
                faultCodeString,
                Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(operationTimestamp);

        ClosureSendData closureSendData = new ClosureSendData(
                transaction.getTransactionId(),
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
                        idCart,
                        TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                        false,
                        "authorizationRequestId",
                        PaymentGateway.VPOS,
                        null,
                        TransactionAuthorizationRequestData.CardBrand.VISA,
                        "paymentMethodDescription"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        "rrn",
                        OffsetDateTime.now().toString(),
                        null,
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

        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(any())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, times(0)).save(any());
    }

    @Test
    void shouldSetTransactionStatusToClosureFailedOnNodoKO() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        TransactionAmount fee = new TransactionAmount(10);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null)),
                        false
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
                        idCart,
                        TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        fee.value(),
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        false,
                        "authorizationRequestId",
                        PaymentGateway.XPAY,
                        URI.create("localhost/logo"),
                        TransactionAuthorizationRequestData.CardBrand.VISA,
                        "paymentMethodDescription"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        AUTHORIZATION_CODE,
                        ECOMMERCE_RRN,
                        OffsetDateTime.now().toString(),
                        OutcomeXpayGatewayDto.ErrorCodeEnum.NUMBER_1.toString(),
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.KO)
                                .authorizationCode(AUTHORIZATION_CODE)
                )
                .timestampOperation(operationTimestamp);

        BigDecimal amountEuroCent = EuroUtils.euroCentsToEuro(
                amount.value()
        );
        BigDecimal feeEuroCent = EuroUtils.euroCentsToEuro(fee.value());
        BigDecimal totalAmountEuroCent = amountEuroCent.add(feeEuroCent);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
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
                                                .creationDate(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getCreationDate().toOffsetDateTime()
                                                )
                                                .fee(feeEuroCent)
                                                .amount(amountEuroCent)
                                                .grandTotal(totalAmountEuroCent)
                                                .transactionStatus("Rifiutato")
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                                .rrn(authorizationCompletedEvent.getData().getRrn())
                                                .authorizationCode(
                                                        authorizationCompletedEvent.getData().getAuthorizationCode()
                                                )
                                                .paymentGateway(authorizationRequestData.getPaymentGateway().name())
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
                                                                .brokerName(authorizationRequestData.getBrokerName())
                                                                .pspOnUs(authorizationRequestData.isPspOnUs())
                                                )
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .errorCode(authorizationCompletedEvent.getData().getErrorCode())
                                )
                                .info(
                                        new InfoDto()
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                ).brandLogo(
                                                        authorizationRequestData.getLogo()
                                                                .toString()
                                                )
                                                .brand(
                                                        authorizationRequestData.getBrand()
                                                                .name()
                                                )
                                                .paymentMethodName(authorizationRequestData.getPaymentMethodName())
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(any())).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT2().isRight());
                    assertNotNull(next.getT2());
                    assertEquals(
                            event.getData().getResponseOutcome(),
                            ((TransactionClosureData) next.getT2().get().getData()).getResponseOutcome()
                    );
                    assertEquals(event.getEventCode(), next.getT2().get().getEventCode());
                    assertEquals(event.getTransactionId(), next.getT2().get().getTransactionId());
                })
                .verifyComplete();
        Mockito.verify(nodeForPspClient, Mockito.times(1)).closePaymentV2(closePaymentRequest);
        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT.toString()
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
        TransactionAmount fee = new TransactionAmount(10);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null)),
                        false
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
                        idCart,
                        TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        fee.value(),
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        false,
                        "authorizationRequestId",
                        PaymentGateway.XPAY,
                        URI.create("localhost/logo"),
                        TransactionAuthorizationRequestData.CardBrand.VISA,
                        "paymentMethodDescription"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        AUTHORIZATION_CODE,
                        ECOMMERCE_RRN,
                        OutcomeXpayGatewayDto.ErrorCodeEnum.NUMBER_1.toString(),
                        OffsetDateTime.now().toString(),
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.KO)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(operationTimestamp);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.OK);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        BigDecimal amountEuroCent = EuroUtils.euroCentsToEuro(
                amount.value()
        );
        BigDecimal feeEuroCent = EuroUtils.euroCentsToEuro(fee.value());
        BigDecimal totalAmountEuroCent = amountEuroCent.add(feeEuroCent);

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
                                                .creationDate(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getCreationDate().toOffsetDateTime()
                                                )
                                                .fee(feeEuroCent)
                                                .amount(amountEuroCent)
                                                .grandTotal(totalAmountEuroCent)
                                                .transactionStatus("Rifiutato")
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                                .rrn(authorizationCompletedEvent.getData().getRrn())
                                                .authorizationCode(
                                                        authorizationCompletedEvent.getData().getAuthorizationCode()
                                                )
                                                .paymentGateway(authorizationRequestData.getPaymentGateway().name())
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
                                                                .brokerName(authorizationRequestData.getBrokerName())
                                                                .pspOnUs(authorizationRequestData.isPspOnUs())
                                                )
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .errorCode(authorizationCompletedEvent.getData().getErrorCode())
                                )
                                .info(
                                        new InfoDto()
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                ).brandLogo(
                                                        Stream.ofNullable(authorizationRequestData.getLogo())
                                                                .filter(logo -> logo != null)
                                                                .map(l -> l.toString())
                                                                .findFirst()
                                                                .orElse(null)
                                                )
                                                .brand(
                                                        authorizationRequestData.getBrand()
                                                                .name()
                                                )
                                                .paymentMethodName(authorizationRequestData.getPaymentMethodName())
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.OK);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(any())).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT2().isRight());
                    assertNotNull(next.getT2());
                    assertEquals(
                            event.getData().getResponseOutcome(),
                            ((TransactionClosureData) next.getT2().get().getData()).getResponseOutcome()
                    );
                    assertEquals(event.getEventCode(), next.getT2().get().getEventCode());
                    assertEquals(event.getTransactionId(), next.getT2().get().getTransactionId());
                })
                .verifyComplete();
        Mockito.verify(nodeForPspClient, Mockito.times(1)).closePaymentV2(closePaymentRequest);
        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                )
        );
    }

    private static Stream<Arguments> closePaymentDateFormat() {
        return Stream.of(
                Arguments.of("2023-05-01T23:59:59.000Z", "2023-05-02T01:59:59"),
                Arguments.of("2023-12-01T23:59:59.000Z", "2023-12-02T00:59:59")
        );
    }

    @ParameterizedTest
    @MethodSource("closePaymentDateFormat")
    void shoulGenerateClosedEventOnAuthorizationOKAndOkNodoClosePayment(
                                                                        String expectedOperationTimestamp,
                                                                        String expectedLocalTime
    ) {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation("77777777777", false, 100, null)),
                        false
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
                        idCart,
                        TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                        false,
                        "authorizationRequestId",
                        PaymentGateway.VPOS,
                        URI.create("test/logo"),
                        TransactionAuthorizationRequestData.CardBrand.VISA,
                        "paymentMethodDescription"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        ECOMMERCE_RRN,
                        expectedOperationTimestamp,
                        null,
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
                .timestampOperation(OffsetDateTime.parse(expectedOperationTimestamp));

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
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
                                        expectedLocalTime
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
                                                .transactionStatus("Confermato")
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
                                                                .brokerName(authorizationRequestData.getBrokerName())
                                                                .pspOnUs(authorizationRequestData.isPspOnUs())
                                                )
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .errorCode(authorizationCompletedEvent.getData().getErrorCode())
                                                .paymentGateway(authorizationRequestData.getPaymentGateway().name())
                                )
                                .info(
                                        new InfoDto()
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                ).brandLogo(
                                                        Stream.ofNullable(authorizationRequestData.getLogo())
                                                                .filter(logo -> logo != null)
                                                                .map(l -> l.toString())
                                                                .findFirst()
                                                                .orElse(null)
                                                )
                                                .brand(
                                                        authorizationRequestData.getBrand()
                                                                .name()
                                                )
                                                .paymentMethodName(authorizationRequestData.getPaymentMethodName())
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.OK);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(any())).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT2().isRight());
                    assertNotNull(next.getT2().get());
                    assertEquals(
                            event.getData().getResponseOutcome(),
                            ((TransactionClosureData) next.getT2().get().getData()).getResponseOutcome()
                    );
                    assertEquals(event.getEventCode(), next.getT2().get().getEventCode());
                    assertEquals(event.getTransactionId(), next.getT2().get().getTransactionId());
                })
                .verifyComplete();
        Mockito.verify(nodeForPspClient, Mockito.times(1)).closePaymentV2(closePaymentRequest);
        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSED_EVENT.toString()
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
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null)),
                        false
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
                        idCart,
                        TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                        false,
                        "authorizationRequestId",
                        PaymentGateway.XPAY,
                        URI.create("test/logo"),
                        TransactionAuthorizationRequestData.CardBrand.VISA,
                        "paymentMethodDescription"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        ECOMMERCE_RRN,
                        OffsetDateTime.now().toString(),
                        null,
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(operationTimestamp);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
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
                        .mapToInt(it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount)
                        .sum()
                        + authorizationRequestData.getFee())
        );

        BigDecimal fee = EuroUtils.euroCentsToEuro(authorizationRequestData.getFee());

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.PaymentNotice::getPaymentToken).toList()
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
                                        expectedOperationTimestamp
                                )
                                .rrn(authorizationCompletedEvent.getData().getRrn())
                                .totalAmount(totalAmount.toString())
                ).transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Confermato")
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
                                                .rrn(ECOMMERCE_RRN)
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
                                                                ).brokerName(authorizationRequestData.getBrokerName())
                                                                .pspOnUs(authorizationRequestData.isPspOnUs())
                                                )
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .errorCode(authorizationCompletedEvent.getData().getErrorCode())
                                                .paymentGateway(authorizationRequestData.getPaymentGateway().name())
                                )
                                .info(
                                        new InfoDto()
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                ).brandLogo(
                                                        Stream.ofNullable(authorizationRequestData.getLogo())
                                                                .filter(logo -> logo != null)
                                                                .map(l -> l.toString())
                                                                .findFirst()
                                                                .orElse(null)
                                                )
                                                .brand(
                                                        authorizationRequestData.getBrand()
                                                                .name()
                                                )
                                                .paymentMethodName(authorizationRequestData.getPaymentMethodName())
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
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())
        ).thenReturn(queueSuccessfulResponse());
        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT2().isLeft());
                    assertNotNull(next.getT2().getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getT2().getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getT2().getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, times(1))
                .save(
                        argThat(
                                e -> e.getEventCode()
                                        .equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT.toString())
                        )
                );
        Mockito.verify(transactionClosureSentEventQueueClient, times(1))
                .sendMessageWithResponse(
                        argThat(
                                (QueueEvent<TransactionClosureErrorEvent> e) -> e.event().equals(errorEvent)
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        any()
                );
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
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
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null)),
                        false
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
                        idCart,
                        TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                        false,
                        "authorizationRequestId",
                        PaymentGateway.VPOS,
                        URI.create("logo/test"),
                        TransactionAuthorizationRequestData.CardBrand.VISA,
                        "paymentMethodDescription"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        ECOMMERCE_RRN,
                        OffsetDateTime.now().toString(),
                        null,
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
                .timestampOperation(operationTimestamp);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                        .mapToInt(it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount)
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
                                        expectedOperationTimestamp
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
                                                .transactionStatus("Confermato")
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
                                                                .pspOnUs(authorizationRequestData.isPspOnUs())
                                                                .brokerName(authorizationRequestData.getBrokerName())
                                                )
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .errorCode(authorizationCompletedEvent.getData().getErrorCode())
                                                .paymentGateway(authorizationRequestData.getPaymentGateway().name())
                                )
                                .info(
                                        new InfoDto()
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                ).brandLogo(
                                                        Stream.ofNullable(authorizationRequestData.getLogo())
                                                                .filter(logo -> logo != null)
                                                                .map(l -> l.toString())
                                                                .findFirst()
                                                                .orElse(null)
                                                )
                                                .brand(
                                                        authorizationRequestData.getBrand()
                                                                .name()
                                                )
                                                .paymentMethodName(authorizationRequestData.getPaymentMethodName())
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
        Mockito.when(nodeForPspClient.closePaymentV2(any())).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        // first call to redis is ko, second one is ok
        Mockito.when(transactionEventStoreRepository.save(any()))
                .thenReturn(Mono.error(redisError));
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any()))
                .thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT2().isLeft());
                    assertNotNull(next.getT2().getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getT2().getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getT2().getLeft().getTransactionId());
                })
                .verifyComplete();
        Mockito.verify(nodeForPspClient, Mockito.times(1)).closePaymentV2(closePaymentRequest);
        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(
                        argThat(
                                e -> e.getEventCode()
                                        .equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT.toString())
                        )
                );

        Mockito.verify(transactionClosureSentEventQueueClient, times(1))
                .sendMessageWithResponse(
                        argThat(
                                (QueueEvent<TransactionClosureErrorEvent> e) -> e.event().equals(errorEvent)
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        any()
                );
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
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
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null)),
                        false
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
                        idCart,
                        TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                        false,
                        "authorizationRequestId",
                        PaymentGateway.XPAY,
                        URI.create("logo/test"),
                        TransactionAuthorizationRequestData.CardBrand.VISA,
                        "paymentMethodDescription"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        ECOMMERCE_RRN,
                        OffsetDateTime.now().toString(),
                        null,
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(operationTimestamp);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                        .mapToInt(it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount)
                        .sum()
                        + authorizationRequestData.getFee())
        );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                                        .mapToInt(it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount)
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
                                        expectedOperationTimestamp
                                )
                                .totalAmount(totalAmount.toString())
                                .rrn(ECOMMERCE_RRN)
                ).transactionDetails(
                        new TransactionDetailsDto()
                                .transaction(
                                        new TransactionDto()
                                                .transactionId(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getTransactionId().value()
                                                )
                                                .transactionStatus("Confermato")
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
                                                .rrn(ECOMMERCE_RRN)
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
                                                                .brokerName(authorizationRequestData.getBrokerName())
                                                                .pspOnUs(authorizationRequestData.isPspOnUs())
                                                )
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .errorCode(authorizationCompletedEvent.getData().getErrorCode())
                                                .paymentGateway(authorizationRequestData.getPaymentGateway().name())
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
                                                .brand(authorizationRequestData.getBrand().name())
                                                .paymentMethodName(authorizationRequestData.getPaymentMethodName())
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );
        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value()
        );

        TransactionRefundRequestedEvent refundRequestedEvent = new TransactionRefundRequestedEvent(
                transactionId.value(),
                new TransactionRefundedData()
        );

        RuntimeException closePaymentError = new BadGatewayException("Bad request error", HttpStatus.BAD_REQUEST);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())
        ).thenReturn(queueSuccessfulResponse());
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenReturn(Mono.just(refundRequestedEvent));
        Mockito.when(
                refundQueueAsyncClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())

        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT1().isPresent());
                    assertNotNull(next.getT1().get());
                    assertEquals(refundRequestedEvent.getEventCode(), next.getT1().get().getEventCode());
                    assertEquals(refundRequestedEvent.getTransactionId(), next.getT1().get().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(
                refundQueueAsyncClient,
                times(1)
        ).sendMessageWithResponse(
                argThat(
                        (QueueEvent<TransactionRefundRequestedEvent> e) -> e.event().getData()
                                .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSURE_ERROR)
                ),
                any(),
                any()
        );
        // check that one closure error event is saved and none is sent to event
        // dispatcher
        Mockito.verify(transactionClosureErrorEventStoreRepository, times(1))
                .save(any());
        Mockito.verify(transactionClosureSentEventQueueClient, times(0))
                .sendMessageWithResponse(
                        argThat(
                                (QueueEvent<TransactionClosureErrorEvent> e) -> e.event().equals(errorEvent)
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
        durationArgumentCaptor.getAllValues()
                .forEach(duration -> assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), duration));
    }

    @Test
    void shouldNotEnqueueErrorEventOnNodoUnrecoverableFailureTransactionNotAuthorized() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        TransactionAmount fee = new TransactionAmount(10);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null)),
                        false
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
                        idCart,
                        TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        fee.value(),
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        false,
                        "authorizationRequestId",
                        PaymentGateway.VPOS,
                        null,
                        TransactionAuthorizationRequestData.CardBrand.VISA,
                        "paymentMethodDescription"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        null,
                        null,
                        OffsetDateTime.now().toString(),
                        OutcomeVposGatewayDto.ErrorCodeEnum._07.toString(),
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.KO)
                )
                .timestampOperation(operationTimestamp);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        BigDecimal amountEuroCent = EuroUtils.euroCentsToEuro(
                amount.value()
        );
        BigDecimal feeEuroCent = EuroUtils.euroCentsToEuro(fee.value());
        BigDecimal totalAmountEuroCent = amountEuroCent.add(feeEuroCent);

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
                                                .creationDate(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getCreationDate().toOffsetDateTime()
                                                )
                                                .fee(feeEuroCent)
                                                .amount(amountEuroCent)
                                                .grandTotal(totalAmountEuroCent)
                                                .transactionStatus("Rifiutato")
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                                .rrn(authorizationCompletedEvent.getData().getRrn())
                                                .authorizationCode(
                                                        authorizationCompletedEvent.getData().getAuthorizationCode()
                                                )
                                                .paymentGateway(
                                                        authorizationRequestedEvent.getData().getPaymentGateway().name()
                                                )
                                                .psp(
                                                        new PspDto()
                                                                .idPsp(
                                                                        authorizationRequestedEvent.getData()
                                                                                .getPspId()
                                                                )
                                                                .idChannel(
                                                                        authorizationRequestedEvent.getData()
                                                                                .getPspChannelCode()
                                                                )
                                                                .businessName(
                                                                        authorizationRequestedEvent.getData()
                                                                                .getPspBusinessName()
                                                                )
                                                                .brokerName(
                                                                        authorizationRequestedEvent.getData()
                                                                                .getBrokerName()
                                                                )
                                                                .pspOnUs(
                                                                        authorizationRequestedEvent.getData()
                                                                                .isPspOnUs()
                                                                )
                                                )
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .errorCode(authorizationCompletedEvent.getData().getErrorCode())
                                )
                                .info(
                                        new InfoDto()
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                                .type(
                                                        authorizationRequestedEvent.getData()
                                                                .getPaymentTypeCode()
                                                ).brandLogo(
                                                        Stream.ofNullable(
                                                                authorizationRequestedEvent.getData().getLogo()
                                                        )
                                                                .filter(logo -> logo != null)
                                                                .map(l -> l.toString())
                                                                .findFirst()
                                                                .orElse(null)
                                                )
                                                .brand(
                                                        authorizationRequestedEvent.getData().getBrand()
                                                                .name()
                                                )
                                                .paymentMethodName(
                                                        authorizationRequestedEvent.getData().getPaymentMethodName()
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
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(
                refundQueueAsyncClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT2().isLeft());
                    assertNotNull(next.getT2().getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getT2().getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getT2().getLeft().getTransactionId());
                })
                .verifyComplete();

        // check that no closure error event is saved but not sent to event dispatcher
        Mockito.verify(transactionClosureErrorEventStoreRepository, times(1))
                .save(any());
        Mockito.verify(transactionClosureSentEventQueueClient, times(0))
                .sendMessageWithResponse(
                        argThat(
                                (QueueEvent<TransactionClosureErrorEvent> e) -> e.event().equals(errorEvent)
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
        // check that closure error event is saved
        Mockito.verify(transactionClosureErrorEventStoreRepository, times(1)).save(any());
    }

    @Test
    void shouldNotEnqueueErrorEventOnNodoRecoverableFailureTransactionNotAuthorized() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        TransactionAmount fee = new TransactionAmount(10);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null)),
                        false
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
                        idCart,
                        TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        fee.value(),
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        false,
                        "authorizationRequestId",
                        PaymentGateway.XPAY,
                        URI.create("logo/test"),
                        TransactionAuthorizationRequestData.CardBrand.VISA,
                        "paymentMethodDescription"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        null,
                        ECOMMERCE_RRN,
                        OffsetDateTime.now().toString(),
                        OutcomeXpayGatewayDto.ErrorCodeEnum.NUMBER_1.toString(),
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.KO)
                )
                .timestampOperation(operationTimestamp);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.KO);

        BigDecimal amountEuroCent = EuroUtils.euroCentsToEuro(
                amount.value()
        );
        BigDecimal feeEuroCent = EuroUtils.euroCentsToEuro(fee.value());
        BigDecimal totalAmountEuroCent = amountEuroCent.add(feeEuroCent);

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
                                                .creationDate(
                                                        ((BaseTransactionWithPaymentToken) transaction)
                                                                .getCreationDate().toOffsetDateTime()
                                                )
                                                .fee(feeEuroCent)
                                                .amount(amountEuroCent)
                                                .grandTotal(totalAmountEuroCent)
                                                .transactionStatus("Rifiutato")
                                                .creationDate(
                                                        ZonedDateTime.parse(
                                                                transactionActivatedEvent
                                                                        .getCreationDate()
                                                        ).toOffsetDateTime()
                                                )
                                                .rrn(authorizationCompletedEvent.getData().getRrn())
                                                .authorizationCode(
                                                        authorizationCompletedEvent.getData().getAuthorizationCode()
                                                )
                                                .paymentGateway(
                                                        authorizationRequestedEvent.getData().getPaymentGateway().name()
                                                )
                                                .psp(
                                                        new PspDto()
                                                                .idPsp(
                                                                        authorizationRequestedEvent.getData()
                                                                                .getPspId()
                                                                )
                                                                .idChannel(
                                                                        authorizationRequestedEvent.getData()
                                                                                .getPspChannelCode()
                                                                )
                                                                .businessName(
                                                                        authorizationRequestedEvent.getData()
                                                                                .getPspBusinessName()
                                                                )
                                                                .brokerName(
                                                                        authorizationRequestedEvent.getData()
                                                                                .getBrokerName()
                                                                )
                                                                .pspOnUs(
                                                                        authorizationRequestedEvent.getData()
                                                                                .isPspOnUs()
                                                                )
                                                )
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .errorCode(authorizationCompletedEvent.getData().getErrorCode())
                                )
                                .info(
                                        new InfoDto()
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                                .type(
                                                        authorizationRequestedEvent.getData()
                                                                .getPaymentTypeCode()
                                                ).brandLogo(
                                                        Stream.ofNullable(
                                                                authorizationRequestedEvent.getData().getLogo()
                                                        )
                                                                .filter(logo -> logo != null)
                                                                .map(l -> l.toString())
                                                                .findFirst()
                                                                .orElse(null)
                                                )
                                                .brand(
                                                        authorizationRequestedEvent.getData().getBrand()
                                                                .name()
                                                )
                                                .paymentMethodName(
                                                        authorizationRequestedEvent.getData().getPaymentMethodName()
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
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT2().isLeft());
                    assertNotNull(next.getT2().getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getT2().getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getT2().getLeft().getTransactionId());
                })
                .verifyComplete();

        // check that no closure error event is saved and sent to event dispatcher
        Mockito.verify(transactionClosureErrorEventStoreRepository, times(1))
                .save(
                        argThat(
                                e -> e.getEventCode()
                                        .equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT.toString())
                        )
                );
        Mockito.verify(transactionClosureSentEventQueueClient, times(1))
                .sendMessageWithResponse(
                        argThat(
                                (QueueEvent<TransactionClosureErrorEvent> e) -> e.event().equals(errorEvent)
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        any()
                );
        // check that closure event with KO status is not saved
        Mockito.verify(transactionEventStoreRepository, times(0)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                                && eventArg.getData().getResponseOutcome().equals(TransactionClosureData.Outcome.KO)
                )
        );
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
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
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null)),
                        false
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
                        idCart,
                        TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                        false,
                        "authorizationRequestId",
                        PaymentGateway.VPOS,
                        URI.create("logo/test"),
                        TransactionAuthorizationRequestData.CardBrand.VISA,
                        "paymentMethodDescription"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        ECOMMERCE_RRN,
                        OffsetDateTime.now().toString(),
                        null,
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
                .timestampOperation(operationTimestamp);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
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
                        .mapToInt(it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount)
                        .sum()
                        + authorizationRequestData.getFee())
        );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                                        .mapToInt(it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount)
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
                                        expectedOperationTimestamp
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
                                                .transactionStatus("Confermato")
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
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .paymentGateway(authorizationRequestData.getPaymentGateway().name())
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
                                                                .pspOnUs(authorizationRequestData.isPspOnUs())
                                                                .brokerName(authorizationRequestData.getBrokerName())
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
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                                .brand(authorizationRequestData.getBrand().name())
                                                .paymentMethodName(authorizationRequestData.getPaymentMethodName())
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
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT2().isLeft());
                    assertNotNull(next.getT2().getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getT2().getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getT2().getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, times(1))
                .save(
                        argThat(
                                e -> e.getEventCode()
                                        .equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT.toString())
                        )
                );
        Mockito.verify(transactionClosureSentEventQueueClient, times(1))
                .sendMessageWithResponse(
                        argThat(
                                (QueueEvent<TransactionClosureErrorEvent> e) -> e.event().equals(errorEvent)
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        any()
                );
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
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
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> paymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null)),
                        false
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
                        idCart,
                        TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                        false,
                        "authorizationRequestId",
                        PaymentGateway.XPAY,
                        URI.create("logo/test"),
                        TransactionAuthorizationRequestData.CardBrand.VISA,
                        "paymentMethodDescription"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        ECOMMERCE_RRN,
                        OffsetDateTime.now().toString(),
                        null,
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(operationTimestamp);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
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
                        .mapToInt(it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount)
                        .sum()
                        + authorizationRequestData.getFee())
        );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.PaymentNotice::getPaymentToken).toList()
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
                                        expectedOperationTimestamp
                                ).rrn(ECOMMERCE_RRN)
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
                                                .transactionStatus("Confermato")
                                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                                                .amount(
                                                        EuroUtils.euroCentsToEuro(
                                                                (transactionActivatedEvent.getData().getPaymentNotices()
                                                                        .stream()
                                                                        .mapToInt(
                                                                                it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount
                                                                        )
                                                                        .sum())
                                                        )
                                                )
                                                .grandTotal(
                                                        EuroUtils.euroCentsToEuro(
                                                                (transactionActivatedEvent.getData().getPaymentNotices()
                                                                        .stream()
                                                                        .mapToInt(
                                                                                it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount
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
                                                                .brokerName(authorizationRequestData.getBrokerName())
                                                                .pspOnUs(authorizationRequestData.isPspOnUs())
                                                )
                                                .rrn(ECOMMERCE_RRN)
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .paymentGateway(authorizationRequestData.getPaymentGateway().name())
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
                                                .brand(authorizationRequestData.getBrand().name())
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                                .paymentMethodName(authorizationRequestData.getPaymentMethodName())
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
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT2().isLeft());
                    assertNotNull(next.getT2().getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getT2().getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getT2().getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, times(1))
                .save(
                        argThat(
                                e -> e.getEventCode()
                                        .equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT.toString())
                        )
                );
        Mockito.verify(transactionClosureSentEventQueueClient, times(1))
                .sendMessageWithResponse(
                        argThat(
                                (QueueEvent<TransactionClosureErrorEvent> e) -> e.event().equals(errorEvent)
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        any()
                );
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
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
                .timestampOperation(operationTimestamp);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosedEvent event = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.KO);

        TransactionRefundRequestedEvent refundRequestedEvent = new TransactionRefundRequestedEvent(
                transactionId.value(),
                new TransactionRefundedData()
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();
        BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                        .mapToInt(it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount)
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
                                        expectedOperationTimestamp
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
                                                .transactionStatus("Confermato")
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
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .paymentGateway(authorizationRequestData.getPaymentGateway().name())
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
                                                                .pspOnUs(authorizationRequestData.isPspOnUs())
                                                                .brokerName(authorizationRequestData.getBrokerName())
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
                                                .paymentMethodName(authorizationRequestData.getPaymentMethodName())
                                                .brand(authorizationRequestData.getBrand().name())
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(any())).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        Mockito.when(
                transactionClosureSentEventQueueClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())
        ).thenReturn(queueSuccessfulResponse());
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenReturn(Mono.just(refundRequestedEvent));
        Mockito.when(
                refundQueueAsyncClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())

        ).thenReturn(queueSuccessfulResponse());

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT1().isPresent());
                    assertNotNull(next.getT1().get());
                    assertEquals(refundRequestedEvent.getEventCode(), next.getT1().get().getEventCode());
                    assertEquals(refundRequestedEvent.getTransactionId(), next.getT1().get().getTransactionId());
                })
                .verifyComplete();
        Mockito.verify(nodeForPspClient, Mockito.times(1)).closePaymentV2(closePaymentRequest);
        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(refundQueueAsyncClient, times(1))
                .sendMessageWithResponse(
                        argThat(
                                (
                                 QueueEvent<TransactionRefundRequestedEvent> e
                                ) -> e.event().getTransactionId().equals(transactionId.value()) && e.event().getData()
                                        .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSED)
                        ),
                        eq(Duration.ZERO),
                        any()
                );
        durationArgumentCaptor.getAllValues()
                .forEach(duration -> assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), duration));
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
                                .authorizationCode(AUTHORIZATION_CODE)
                                .rrn(ECOMMERCE_RRN)
                )
                .timestampOperation(operationTimestamp);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();
        BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                        .mapToInt(it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount)
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
                                        AUTHORIZATION_CODE
                                )
                                .rrn(ECOMMERCE_RRN)
                                .fee(
                                        EuroUtils.euroCentsToEuro(authorizationRequestData.getFee())
                                                .toString()
                                )
                                .timestampOperation(
                                        expectedOperationTimestamp
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
                                                .transactionStatus("Confermato")
                                                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                                                .paymentGateway(authorizationRequestData.getPaymentGateway().name())
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
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
                                                        ECOMMERCE_RRN
                                                )
                                                .authorizationCode(
                                                        AUTHORIZATION_CODE
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
                                                                .brokerName(authorizationRequestData.getBrokerName())
                                                                .pspOnUs(authorizationRequestData.isPspOnUs())
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
                                                .brand(authorizationRequestData.getBrand().name())
                                                .paymentMethodName(authorizationRequestData.getPaymentMethodName())
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))

                );

        RuntimeException closePaymentError = new BadGatewayException("Bad request error", HttpStatus.BAD_REQUEST);

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value()
        );

        TransactionRefundRequestedEvent refundRequestedEvent = new TransactionRefundRequestedEvent(
                transactionId.value(),
                new TransactionRefundedData()
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        Mockito.when(
                refundQueueAsyncClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())
        ).thenReturn(queueSuccessfulResponse());
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenReturn(Mono.just(refundRequestedEvent));
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any()))
                .thenAnswer(a -> Mono.just(a.getArgument(0)));

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT1().isPresent());
                    assertNotNull(next.getT1().get());
                    assertEquals(refundRequestedEvent.getEventCode(), next.getT1().get().getEventCode());
                    assertEquals(refundRequestedEvent.getTransactionId(), next.getT1().get().getTransactionId());
                })
                .verifyComplete();

        /*
         * check that the closure event with outcome KO is sent in the transaction
         * activated queue
         */
        Mockito.verify(refundQueueAsyncClient, times(1))
                .sendMessageWithResponse(
                        argThat(
                                (
                                 QueueEvent<TransactionRefundRequestedEvent> e
                                ) -> e.event().getTransactionId().equals(transactionId.value()) && e.event().getData()
                                        .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSURE_ERROR)
                        ),
                        eq(Duration.ZERO),
                        any()
                );

        /*
         * check that no event is sent on the closure error queue
         */
        Mockito.verify(transactionClosureSentEventQueueClient, times(0))
                .sendMessageWithResponse(
                        any(),
                        any(),
                        isNull()
                );
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
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
                .timestampOperation(operationTimestamp);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.KO);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        BigDecimal amount = EuroUtils.euroCentsToEuro(
                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                        .mapToInt(
                                paymentNotice -> paymentNotice.transactionAmount().value()
                        ).sum()
        );
        BigDecimal fee = EuroUtils.euroCentsToEuro(authorizationRequestData.getFee());
        BigDecimal totalAmount = amount.add(fee);

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
                                                .psp(
                                                        new PspDto()
                                                                .pspOnUs(authorizationRequestData.isPspOnUs())
                                                                .idPsp(authorizationRequestData.getPspId())
                                                                .brokerName(authorizationRequestData.getBrokerName())
                                                                .idChannel(authorizationRequestData.getPspChannelCode())
                                                                .businessName(
                                                                        authorizationRequestData.getPspBusinessName()
                                                                )
                                                )
                                                .paymentGateway(authorizationRequestData.getPaymentGateway().name())
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .authorizationCode(
                                                        ((OutcomeVposGatewayDto) (updateAuthorizationRequest
                                                                .getOutcomeGateway())).getAuthorizationCode()
                                                )
                                                .rrn(
                                                        ((OutcomeVposGatewayDto) (updateAuthorizationRequest
                                                                .getOutcomeGateway())).getRrn()
                                                )
                                                .fee(fee)
                                                .amount(amount)
                                                .grandTotal(totalAmount)
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                                .brand(authorizationRequestData.getBrand().name())
                                                .brandLogo(authorizationRequestData.getLogo().toString())
                                                .paymentMethodName(authorizationRequestData.getPaymentMethodName())
                                )
                                .user(new UserDto().type(UserDto.TypeEnum.GUEST))
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(any())).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        Mockito.when(
                transactionClosureSentEventQueueClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())
        ).thenReturn(queueSuccessfulResponse());

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT2().isRight());
                    assertNotNull(next.getT2().get());
                    assertEquals(
                            event.getData().getResponseOutcome(),
                            ((TransactionClosureData) next.getT2().get().getData()).getResponseOutcome()
                    );
                    assertEquals(event.getEventCode(), next.getT2().get().getEventCode());
                    assertEquals(event.getTransactionId(), next.getT2().get().getTransactionId());
                })
                .verifyComplete();
        Mockito.verify(nodeForPspClient, Mockito.times(1)).closePaymentV2(closePaymentRequest);
        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(transactionClosureSentEventQueueClient, times(0))
                .sendMessageWithResponse(
                        any(),
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
                .timestampOperation(operationTimestamp);

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                transactionId,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        BigDecimal amount = EuroUtils.euroCentsToEuro(
                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                        .mapToInt(
                                paymentNotice -> paymentNotice.transactionAmount().value()
                        ).sum()
        );
        BigDecimal fee = EuroUtils.euroCentsToEuro(authorizationRequestData.getFee());
        BigDecimal totalAmount = amount.add(fee);

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
                                                .psp(
                                                        new PspDto()
                                                                .pspOnUs(authorizationRequestData.isPspOnUs())
                                                                .idPsp(authorizationRequestData.getPspId())
                                                                .brokerName(authorizationRequestData.getBrokerName())
                                                                .idChannel(authorizationRequestData.getPspChannelCode())
                                                                .businessName(
                                                                        authorizationRequestData.getPspBusinessName()
                                                                )
                                                )
                                                .paymentGateway(authorizationRequestData.getPaymentGateway().name())
                                                .rrn(
                                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getRrn()
                                                )
                                                .authorizationCode(
                                                        ((OutcomeVposGatewayDto) updateAuthorizationRequest
                                                                .getOutcomeGateway()).getAuthorizationCode()
                                                )
                                                .timestampOperation(
                                                        authorizationCompletedEvent.getData().getTimestampOperation()
                                                )
                                                .fee(fee)
                                                .amount(amount)
                                                .grandTotal(totalAmount)
                                )
                                .info(
                                        new InfoDto()
                                                .type(
                                                        authorizationRequestData
                                                                .getPaymentTypeCode()
                                                )
                                                .clientId(
                                                        ((BaseTransactionWithPaymentToken) transaction).getClientId()
                                                                .name()
                                                )
                                                .brand(authorizationRequestData.getBrand().name())
                                                .brandLogo(authorizationRequestData.getLogo().toString())
                                                .paymentMethodName(authorizationRequestData.getPaymentMethodName())
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
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        Mockito.when(
                refundQueueAsyncClient
                        .sendMessageWithResponse(any(), any(), durationArgumentCaptor.capture())
        ).thenReturn(queueSuccessfulResponse());
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any()))
                .thenAnswer(a -> Mono.just(a.getArgument(0)));

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.getT2().isLeft());
                    assertNotNull(next.getT2().getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getT2().getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getT2().getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, times(1)).save(any());
        Mockito.verify(transactionClosureSentEventQueueClient, times(0))
                .sendMessageWithResponse(
                        any(),
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
