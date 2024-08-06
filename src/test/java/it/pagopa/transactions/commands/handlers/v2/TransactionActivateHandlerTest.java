package it.pagopa.transactions.commands.handlers.v2;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionEventCode;
import it.pagopa.ecommerce.commons.exceptions.JWTTokenGenerationException;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.queues.TracingUtilsTests;
import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManagerTest;
import it.pagopa.ecommerce.commons.utils.JwtTokenUtils;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.generated.transactions.server.model.PaymentInfoDto;
import it.pagopa.generated.transactions.server.model.PaymentNoticeInfoDto;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.commands.data.NewTransactionRequestData;
import it.pagopa.transactions.configurations.SecretsConfigurations;
import it.pagopa.transactions.exceptions.InvalidNodoResponseException;
import it.pagopa.transactions.projections.TransactionsProjection;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.NodoOperations;
import it.pagopa.transactions.utils.Queues;
import it.pagopa.transactions.utils.SpanLabelOpenTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static it.pagopa.ecommerce.commons.v2.TransactionTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class TransactionActivateHandlerTest {

    private final PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper = Mockito
            .mock(PaymentRequestInfoRedisTemplateWrapper.class);

    private final TransactionsEventStoreRepository<TransactionActivatedData> transactionEventActivatedStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final NodoOperations nodoOperations = Mockito.mock(NodoOperations.class);

    private final QueueAsyncClient transactionActivatedQueueAsyncClient = Mockito.mock(QueueAsyncClient.class);

    private final JwtTokenUtils jwtTokenUtils = Mockito.mock(JwtTokenUtils.class);

    private final ConfidentialMailUtils confidentialMailUtils = Mockito.mock(ConfidentialMailUtils.class);

    private final int paymentTokenTimeout = 120;

    private final int nodoParallelRequests = 5;

    private final int transientQueueEventsTtlSeconds = 30;

    private final String dueDate = "2031-12-31";

    private final TracingUtils tracingUtils = TracingUtilsTests.getMock();

    @Captor
    private ArgumentCaptor<Duration> durationArgumentCaptor;

    @Captor
    private ArgumentCaptor<PaymentRequestInfo> paymentRequestInfoArgumentCaptor;

    private final OpenTelemetryUtils openTelemetryUtils = Mockito.mock(OpenTelemetryUtils.class);

    private static final String ORDER_ID = "orderId";

    private static final UUID CORRELATION_ID = UUID.randomUUID();

    private static final String STRONG_KEY = "ODMzNUZBNTZENDg3NTYyREUyNDhGNDdCRUZDNzI3NDMzMzQwNTFEREZGQ0MyQzA5Mjc1RjY2NTQ1NDk5MDMxNzU5NDc0NUVFMTdDMDhGNzk4Q0Q3RENFMEJBODE1NURDREExNEY2Mzk4QzFEMTU0NTExNjUyMEExMzMwMTdDMDk";

    private static final int tokenValidityTimeInSeconds = 900;

    private final SecretKey jwtSecretKey = new SecretsConfigurations().ecommerceSigningKey(STRONG_KEY);

    private final UUID userId = UUID.randomUUID();

    private ConfidentialDataManager confidentialDataManager = ConfidentialDataManagerTest.getMock();

    private final TransactionActivateHandler handler = new TransactionActivateHandler(
            paymentRequestInfoRedisTemplateWrapper,
            transactionEventActivatedStoreRepository,
            nodoOperations,
            jwtTokenUtils,
            transactionActivatedQueueAsyncClient,
            paymentTokenTimeout,
            confidentialMailUtils,
            transientQueueEventsTtlSeconds,
            nodoParallelRequests,
            tracingUtils,
            openTelemetryUtils,
            jwtSecretKey,
            tokenValidityTimeInSeconds
    );

    @BeforeEach
    void setup() {
        Mockito.reset(transactionEventActivatedStoreRepository, confidentialMailUtils);
        Mockito.when(transactionEventActivatedStoreRepository.save(any()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(confidentialMailUtils.toConfidential(EMAIL_STRING)).thenReturn(Mono.just(EMAIL));
    }

    @Test
    void shouldHandleCommandForNM3CachedPaymentRequestWithNpgWithV2Api() {
        Duration elapsedTimeFromActivation = Duration.ofSeconds(paymentTokenTimeout);
        ZonedDateTime transactionActivatedTime = ZonedDateTime.now().minus(elapsedTimeFromActivation);
        RptId rptId = new RptId(RPT_ID);
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = PAYMENT_TOKEN;
        String paName = "paName";
        String paTaxcode = rptId.getFiscalCode();
        TransactionId transactionId = new TransactionId(TRANSACTION_ID);
        it.pagopa.generated.transactions.v2.server.model.NewTransactionRequestDto requestDto = new it.pagopa.generated.transactions.v2.server.model.NewTransactionRequestDto();
        it.pagopa.generated.transactions.v2.server.model.PaymentNoticeInfoDto paymentNoticeInfoDto = new it.pagopa.generated.transactions.v2.server.model.PaymentNoticeInfoDto();
        requestDto.addPaymentNoticesItem(paymentNoticeInfoDto);
        paymentNoticeInfoDto.setRptId(rptId.value());
        requestDto.setEmail(EMAIL_STRING);
        requestDto.setOrderId(ORDER_ID);
        paymentNoticeInfoDto.setAmount(1200);
        TransactionActivateCommand command = new TransactionActivateCommand(
                List.of(rptId),
                new NewTransactionRequestData(
                        requestDto.getIdCart(),
                        confidentialDataManager.encrypt(new Email(requestDto.getEmail())),
                        requestDto.getOrderId(),
                        CORRELATION_ID,
                        requestDto.getPaymentNotices().stream().map(
                                el -> new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                        null,
                                        new RptId(el.getRptId()),
                                        new TransactionAmount(el.getAmount()),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null
                                )
                        ).toList()
                ),
                Transaction.ClientId.CHECKOUT.name(),
                transactionId,
                userId
        );

        PaymentRequestInfo paymentRequestInfoCached = new PaymentRequestInfo(
                rptId,
                paTaxcode,
                paName,
                DESCRIPTION,
                AMOUNT,
                dueDate,
                paymentToken,
                transactionActivatedTime.toString(),
                idempotencyKey,
                List.of(new PaymentTransferInfo(rptId.getFiscalCode(), false, AMOUNT, null)),
                false,
                null
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent();
        transactionActivatedEvent.setTransactionId(transactionId.value());
        transactionActivatedEvent.setEventCode(TransactionEventCode.TRANSACTION_ACTIVATED_EVENT.toString());
        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setPaymentNotices(
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId.value(),
                                null,
                                null,
                                null,
                                List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, null, null)),
                                false,
                                null,
                                null
                        )
                )
        );
        transactionActivatedEvent.setData(transactionActivatedData);

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.of(paymentRequestInfoCached));

        Mockito.doNothing().when(paymentRequestInfoRedisTemplateWrapper)
                .save(paymentRequestInfoArgumentCaptor.capture());
        Mockito.when(
                transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        Mockito.when(
                jwtTokenUtils.generateToken(
                        eq(jwtSecretKey),
                        eq(tokenValidityTimeInSeconds),
                        eq(new Claims(transactionId, requestDto.getOrderId(), null, userId))
                )
        )
                .thenReturn(Either.right("authToken"));

        /* run test */
        Tuple2<Mono<BaseTransactionEvent<?>>, String> response = handler
                .handle(command).block();

        /* asserts */
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(1)).findById(rptId.value());
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(0)).save(any());
        Mockito.verify(openTelemetryUtils, Mockito.times(1)).addSpanWithAttributes(
                eq(SpanLabelOpenTelemetry.REPEATED_ACTIVATION_SPAN_NAME),
                argThat(
                        arguments -> {
                            String spanPaymentToken = arguments.get(
                                    SpanLabelOpenTelemetry.REPEATED_ACTIVATION_PAYMENT_TOKEN_ATTRIBUTE_KEY
                            );
                            Long spanLeftTime = arguments.get(
                                    SpanLabelOpenTelemetry.REPEATED_ACTIVATION_PAYMENT_TOKEN_LEFT_TIME_ATTRIBUTE_KEY
                            );
                            return paymentToken.equals(spanPaymentToken) && spanLeftTime != null;
                        }
                )
        );
        Mockito.verify(openTelemetryUtils, Mockito.times(0)).addErrorSpanWithException(any(), any());
        assertNotNull(paymentRequestInfoCached.id());
        TransactionActivatedEvent event = (TransactionActivatedEvent) response.getT1().block();

        assertNotNull(event.getTransactionId());
        assertInstanceOf(
                NpgTransactionGatewayActivationData.class,
                event.getData().getTransactionGatewayActivationData()
        );
        assertNotNull(
                ((NpgTransactionGatewayActivationData) event.getData().getTransactionGatewayActivationData())
                        .getOrderId()
        );
        assertEquals(
                ORDER_ID,
                ((NpgTransactionGatewayActivationData) event.getData().getTransactionGatewayActivationData())
                        .getOrderId()
        );
        assertEquals(
                CORRELATION_ID.toString(),
                ((NpgTransactionGatewayActivationData) event.getData().getTransactionGatewayActivationData())
                        .getCorrelationId()
        );
        assertNotNull(event.getEventCode());
        assertNotNull(event.getCreationDate());
        assertNotNull(event.getId());
        assertEquals(paymentTokenTimeout, event.getData().getPaymentTokenValiditySeconds());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());

    }

    @Test
    void shouldHandleCommandForNM3CachedPaymentRequestWithPGS() {
        Duration elapsedTimeFromActivation = Duration.ofSeconds(paymentTokenTimeout);
        ZonedDateTime transactionActivatedTime = ZonedDateTime.now().minus(elapsedTimeFromActivation);
        RptId rptId = new RptId(RPT_ID);
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = PAYMENT_TOKEN;
        String paName = "paName";
        String paTaxcode = rptId.getFiscalCode();
        TransactionId transactionId = new TransactionId(TRANSACTION_ID);
        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        PaymentNoticeInfoDto paymentNoticeInfoDto = new PaymentNoticeInfoDto();
        requestDto.addPaymentNoticesItem(paymentNoticeInfoDto);
        paymentNoticeInfoDto.setRptId(rptId.value());
        requestDto.setEmail(EMAIL_STRING);
        paymentNoticeInfoDto.setAmount(1200);
        TransactionActivateCommand command = new TransactionActivateCommand(
                List.of(rptId),
                new NewTransactionRequestData(
                        requestDto.getIdCart(),
                        confidentialDataManager.encrypt(new Email(requestDto.getEmail())),
                        null,
                        null,
                        requestDto.getPaymentNotices().stream().map(
                                el -> new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                        null,
                                        new RptId(el.getRptId()),
                                        new TransactionAmount(el.getAmount()),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null
                                )
                        ).toList()
                ),
                Transaction.ClientId.CHECKOUT.name(),
                transactionId,
                userId
        );

        PaymentRequestInfo paymentRequestInfoCached = new PaymentRequestInfo(
                rptId,
                paTaxcode,
                paName,
                DESCRIPTION,
                AMOUNT,
                dueDate,
                paymentToken,
                transactionActivatedTime.toString(),
                idempotencyKey,
                List.of(new PaymentTransferInfo(rptId.getFiscalCode(), false, AMOUNT, null)),
                false,
                null
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent();
        transactionActivatedEvent.setTransactionId(transactionId.value());
        transactionActivatedEvent.setEventCode(TransactionEventCode.TRANSACTION_ACTIVATED_EVENT.toString());
        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setPaymentNotices(
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId.value(),
                                null,
                                null,
                                null,
                                List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, null, null)),
                                false,
                                null,
                                null
                        )
                )
        );
        transactionActivatedEvent.setData(transactionActivatedData);

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.of(paymentRequestInfoCached));

        Mockito.doNothing().when(paymentRequestInfoRedisTemplateWrapper)
                .save(paymentRequestInfoArgumentCaptor.capture());
        Mockito.when(
                transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        Mockito.when(
                jwtTokenUtils.generateToken(
                        eq(jwtSecretKey),
                        eq(tokenValidityTimeInSeconds),
                        eq(new Claims(transactionId, null, null, userId))
                )
        )
                .thenReturn(Either.right("authToken"));

        /* run test */
        Tuple2<Mono<BaseTransactionEvent<?>>, String> response = handler
                .handle(command).block();

        /* asserts */
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(1)).findById(rptId.value());
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(0)).save(any());
        Mockito.verify(openTelemetryUtils, Mockito.times(1)).addSpanWithAttributes(
                eq(SpanLabelOpenTelemetry.REPEATED_ACTIVATION_SPAN_NAME),
                argThat(
                        arguments -> {
                            String spanPaymentToken = arguments.get(
                                    SpanLabelOpenTelemetry.REPEATED_ACTIVATION_PAYMENT_TOKEN_ATTRIBUTE_KEY
                            );
                            Long spanLeftTime = arguments.get(
                                    SpanLabelOpenTelemetry.REPEATED_ACTIVATION_PAYMENT_TOKEN_LEFT_TIME_ATTRIBUTE_KEY
                            );
                            return paymentToken.equals(spanPaymentToken) && spanLeftTime != null;
                        }
                )
        );
        Mockito.verify(openTelemetryUtils, Mockito.times(0)).addErrorSpanWithException(any(), any());
        assertNotNull(paymentRequestInfoCached.id());
        TransactionActivatedEvent event = (TransactionActivatedEvent) response.getT1().block();

        assertNotNull(event.getTransactionId());
        assertInstanceOf(
                EmptyTransactionGatewayActivationData.class,
                event.getData().getTransactionGatewayActivationData()
        );
        assertNotNull(event.getEventCode());
        assertNotNull(event.getCreationDate());
        assertNotNull(event.getId());
        assertEquals(paymentTokenTimeout, event.getData().getPaymentTokenValiditySeconds());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());

    }

    @Test
    void shouldHandleCommandForNM3CachedPaymentRequestWithoutActivationDate() {
        RptId rptId = new RptId(RPT_ID);
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        TransactionId transactionId = new TransactionId(TRANSACTION_ID);
        String paymentToken = PAYMENT_TOKEN;
        String paName = "paName";
        String paTaxcode = rptId.getFiscalCode();

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        PaymentNoticeInfoDto paymentNoticeInfoDto = new PaymentNoticeInfoDto();
        requestDto.addPaymentNoticesItem(paymentNoticeInfoDto);
        paymentNoticeInfoDto.setRptId(rptId.value());
        requestDto.setEmail(EMAIL_STRING);
        paymentNoticeInfoDto.setAmount(1200);
        TransactionActivateCommand command = new TransactionActivateCommand(
                List.of(rptId),
                new NewTransactionRequestData(
                        requestDto.getIdCart(),
                        confidentialDataManager.encrypt(new Email(requestDto.getEmail())),
                        null,
                        null,
                        requestDto.getPaymentNotices().stream().map(
                                el -> new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                        null,
                                        new RptId(el.getRptId()),
                                        new TransactionAmount(el.getAmount()),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null
                                )
                        ).toList()
                ),
                Transaction.ClientId.CHECKOUT.name(),
                transactionId,
                userId
        );

        PaymentRequestInfo paymentRequestInfoCached = new PaymentRequestInfo(
                rptId,
                paTaxcode,
                paName,
                DESCRIPTION,
                AMOUNT,
                null,
                paymentToken,
                null,
                idempotencyKey,
                List.of(new PaymentTransferInfo(rptId.getFiscalCode(), false, AMOUNT, null)),
                false,
                null
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent();
        transactionActivatedEvent.setTransactionId(transactionId.value());
        transactionActivatedEvent.setEventCode(TransactionEventCode.TRANSACTION_ACTIVATED_EVENT.toString());
        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setPaymentNotices(
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId.value(),
                                null,
                                null,
                                null,
                                List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, null, null)),
                                false,
                                null,
                                null
                        )
                )
        );
        transactionActivatedEvent.setData(transactionActivatedData);

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.of(paymentRequestInfoCached));

        Mockito.when(
                transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        Mockito.when(
                jwtTokenUtils.generateToken(
                        eq(jwtSecretKey),
                        eq(tokenValidityTimeInSeconds),
                        eq(new Claims(transactionId, null, null, userId))
                )
        )
                .thenReturn(Either.right("authToken"));

        /* run test */
        Tuple2<Mono<BaseTransactionEvent<?>>, String> response = handler
                .handle(command).block();

        /* asserts */
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(1)).findById(rptId.value());
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(0)).save(any());
        Mockito.verify(openTelemetryUtils, Mockito.times(0)).addSpanWithAttributes(any(), any());
        Mockito.verify(openTelemetryUtils, Mockito.times(1)).addErrorSpanWithException(
                eq(SpanLabelOpenTelemetry.REPEATED_ACTIVATION_SPAN_NAME),
                argThat(throwable -> throwable.getMessage().contains(rptId.value()))
        );
        assertNotNull(paymentRequestInfoCached.id());
        TransactionActivatedEvent event = (TransactionActivatedEvent) response.getT1().block();

        assertNotNull(event.getTransactionId());
        assertNotNull(event.getEventCode());
        assertNotNull(event.getCreationDate());
        assertNotNull(event.getId());
        assertEquals(paymentTokenTimeout, event.getData().getPaymentTokenValiditySeconds());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());

    }

    @Test
    void shouldFailForTokenGenerationError() {
        RptId rptId = new RptId("77777777777302016723749670035");
        TransactionId transactionId = new TransactionId(TRANSACTION_ID);
        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        PaymentNoticeInfoDto paymentNoticeInfoDto = new PaymentNoticeInfoDto();
        requestDto.addPaymentNoticesItem(paymentNoticeInfoDto);
        paymentNoticeInfoDto.setRptId(rptId.value());
        requestDto.setEmail("jhon.doe@email.com");
        paymentNoticeInfoDto.setAmount(1200);
        TransactionActivateCommand command = new TransactionActivateCommand(
                List.of(rptId),
                new NewTransactionRequestData(
                        requestDto.getIdCart(),
                        confidentialDataManager.encrypt(new Email(requestDto.getEmail())),
                        null,
                        null,
                        requestDto.getPaymentNotices().stream().map(
                                el -> new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                        null,
                                        new RptId(el.getRptId()),
                                        new TransactionAmount(el.getAmount()),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null
                                )
                        ).toList()
                ),
                Transaction.ClientId.CHECKOUT.name(),
                transactionId,
                userId
        );

        /* preconditions */

        Mockito.when(jwtTokenUtils.generateToken(eq(jwtSecretKey), eq(tokenValidityTimeInSeconds), any()))
                .thenReturn(Either.left(new JWTTokenGenerationException()));

        /* run test */
        StepVerifier
                .create(handler.handle(command))
                .expectErrorMatches(exception -> exception instanceof JWTTokenGenerationException);

    }

    @Test
    void transactionsProjectionTests() {
        String TEST_RPTID = "77777777777302016723749670035";
        String TEST_TOKEN = "token";

        TransactionsProjection<NewTransactionResponseDto> transactionsProjection = new TransactionsProjection<>();
        transactionsProjection.setData(
                new NewTransactionResponseDto()
                        .addPaymentsItem(
                                new PaymentInfoDto()
                                        .amount(1)
                                        .rptId(TEST_RPTID)
                                        .paymentToken(TEST_TOKEN)
                                        .reason("")
                        )
                        .authToken(TEST_TOKEN)
        );

        TransactionsProjection<NewTransactionResponseDto> differentTransactionsProjection = new TransactionsProjection<>();
        differentTransactionsProjection.setData(
                new NewTransactionResponseDto()
                        .addPaymentsItem(
                                new PaymentInfoDto()
                                        .amount(1)
                                        .rptId(TEST_RPTID)
                                        .paymentToken(TEST_TOKEN)
                                        .reason("")
                        )
                        .authToken(TEST_TOKEN)
        );

        differentTransactionsProjection.setRptId(new RptId(TEST_RPTID));

        assertNotEquals(transactionsProjection, differentTransactionsProjection);
        assertEquals(
                Boolean.TRUE,
                transactionsProjection.getData().equals(differentTransactionsProjection.getData())
        );
    }

    @Test
    void shouldFailForMissingNodoResponsePaymentToken() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paName = "paName";
        String paTaxcode = "77777777777";
        String description = "Description";
        Integer amount = 1000;
        TransactionId transactionId = new TransactionId(TRANSACTION_ID);
        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        PaymentNoticeInfoDto paymentNoticeInfoDto = new PaymentNoticeInfoDto();
        requestDto.addPaymentNoticesItem(paymentNoticeInfoDto);
        paymentNoticeInfoDto.setRptId(rptId.value());
        requestDto.setEmail("jhon.doe@email.com");
        paymentNoticeInfoDto.setAmount(1200);
        TransactionActivateCommand command = new TransactionActivateCommand(
                List.of(rptId),
                new NewTransactionRequestData(
                        requestDto.getIdCart(),
                        confidentialDataManager.encrypt(new Email(requestDto.getEmail())),
                        null,
                        null,
                        requestDto.getPaymentNotices().stream().map(
                                el -> new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                        null,
                                        new RptId(el.getRptId()),
                                        new TransactionAmount(el.getAmount()),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null
                                )
                        ).toList()
                ),
                Transaction.ClientId.CHECKOUT.name(),
                transactionId,
                userId
        );

        PaymentRequestInfo paymentRequestInfoCached = new PaymentRequestInfo(
                rptId,
                paTaxcode,
                paName,
                description,
                amount,
                dueDate,
                null,
                null,
                idempotencyKey,
                List.of(new PaymentTransferInfo(rptId.value().substring(0, 11), false, amount, null)),
                false,
                null
        );

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.of(paymentRequestInfoCached));
        Mockito.when(
                nodoOperations.activatePaymentRequest(any(), any(), any(), any(), any(), any(), eq(dueDate), any())
        )
                .thenReturn(Mono.error(new InvalidNodoResponseException("Invalid payment token received")));

        /* run test */
        Mono<Tuple2<Mono<BaseTransactionEvent<?>>, String>> response = handler
                .handle(command);
        /* Assertions */
        InvalidNodoResponseException exception = assertThrows(InvalidNodoResponseException.class, response::block);
        assertEquals("Invalid payment token received", exception.getErrorDescription());

    }

    @Test
    void shouldHandleCommandForOnlyIdempotencyKeyCachedPaymentRequest() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();
        PaymentNotice paymentNotice = transactionActivatedEvent.getData().getPaymentNotices().get(0);
        TransactionId transactionId = new TransactionId(TRANSACTION_ID);
        RptId rptId = new RptId(paymentNotice.getRptId());
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paName = "paName";
        String paTaxcode = rptId.getFiscalCode();

        PaymentNoticeInfoDto paymentNoticeInfoDto = new PaymentNoticeInfoDto()
                .rptId(rptId.value())
                .amount(paymentNotice.getAmount());

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(paymentNoticeInfoDto)
                .email(EMAIL_STRING);

        TransactionActivateCommand command = new TransactionActivateCommand(
                List.of(rptId),
                new NewTransactionRequestData(
                        requestDto.getIdCart(),
                        confidentialDataManager.encrypt(new Email(requestDto.getEmail())),
                        null,
                        null,
                        requestDto.getPaymentNotices().stream().map(
                                el -> new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                        null,
                                        new RptId(el.getRptId()),
                                        new TransactionAmount(el.getAmount()),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null
                                )
                        ).toList()
                ),
                Transaction.ClientId.CHECKOUT.name(),
                transactionId,
                userId
        );

        PaymentRequestInfo paymentRequestInfoBeforeActivation = new PaymentRequestInfo(
                rptId,
                null,
                null,
                null,
                null,
                dueDate,
                null,
                null,
                idempotencyKey,
                List.of(new PaymentTransferInfo(rptId.getFiscalCode(), false, null, null)),
                false,
                null
        );

        PaymentRequestInfo paymentRequestInfoAfterActivation = new PaymentRequestInfo(
                rptId,
                paTaxcode,
                paName,
                paymentNotice.getDescription(),
                paymentNotice.getAmount(),
                dueDate,
                paymentNotice.getPaymentToken(),
                ZonedDateTime.now().toString(),
                idempotencyKey,
                List.of(new PaymentTransferInfo(rptId.getFiscalCode(), false, paymentNotice.getAmount(), null)),
                false,
                null
        );

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.of(paymentRequestInfoBeforeActivation));

        Mockito.doNothing().when(paymentRequestInfoRedisTemplateWrapper)
                .save(paymentRequestInfoArgumentCaptor.capture());
        Mockito.when(
                nodoOperations.activatePaymentRequest(any(), any(), any(), any(), any(), any(), eq(dueDate), any())
        )
                .thenReturn(Mono.just(paymentRequestInfoAfterActivation));
        Mockito.when(
                jwtTokenUtils.generateToken(
                        eq(jwtSecretKey),
                        eq(tokenValidityTimeInSeconds),
                        eq(new Claims(transactionId, null, null, userId))
                )
        )
                .thenReturn(Either.right("authToken"));
        Mockito.when(
                transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                        any(),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        /* run test */
        Tuple2<Mono<BaseTransactionEvent<?>>, String> response = handler
                .handle(command).block();

        /* asserts */
        TransactionActivatedEvent event = (TransactionActivatedEvent) response.getT1().block();
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(1)).findById(rptId.value());
        assertNotNull(event.getTransactionId());
        assertNotNull(event.getEventCode());
        assertNotNull(event.getCreationDate());
        assertNotNull(event.getId());
        assertEquals(paymentTokenTimeout, event.getData().getPaymentTokenValiditySeconds());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
        assertEquals(dueDate, paymentRequestInfoArgumentCaptor.getValue().dueDate());
    }

    @Test
    void shouldHandleCommandForOnlyIdempotencyKeyCachedPaymentRequestWithoutDueDate() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();
        PaymentNotice paymentNotice = transactionActivatedEvent.getData().getPaymentNotices().get(0);
        TransactionId transactionId = new TransactionId(TRANSACTION_ID);
        RptId rptId = new RptId(paymentNotice.getRptId());
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paName = "paName";
        String paTaxcode = rptId.getFiscalCode();

        PaymentNoticeInfoDto paymentNoticeInfoDto = new PaymentNoticeInfoDto()
                .rptId(rptId.value())
                .amount(paymentNotice.getAmount());

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(paymentNoticeInfoDto)
                .email(EMAIL_STRING);

        TransactionActivateCommand command = new TransactionActivateCommand(
                List.of(rptId),
                new NewTransactionRequestData(
                        requestDto.getIdCart(),
                        confidentialDataManager.encrypt(new Email(requestDto.getEmail())),
                        null,
                        null,
                        requestDto.getPaymentNotices().stream().map(
                                el -> new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                        null,
                                        new RptId(el.getRptId()),
                                        new TransactionAmount(el.getAmount()),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null
                                )
                        ).toList()
                ),
                Transaction.ClientId.CHECKOUT.name(),
                transactionId,
                userId
        );

        PaymentRequestInfo paymentRequestInfoBeforeActivation = new PaymentRequestInfo(
                rptId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                idempotencyKey,
                List.of(new PaymentTransferInfo(rptId.getFiscalCode(), false, null, null)),
                false,
                null
        );

        PaymentRequestInfo paymentRequestInfoAfterActivation = new PaymentRequestInfo(
                rptId,
                paTaxcode,
                paName,
                paymentNotice.getDescription(),
                paymentNotice.getAmount(),
                null,
                paymentNotice.getPaymentToken(),
                ZonedDateTime.now().toString(),
                idempotencyKey,
                List.of(new PaymentTransferInfo(rptId.getFiscalCode(), false, paymentNotice.getAmount(), null)),
                false,
                null
        );

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.of(paymentRequestInfoBeforeActivation));

        Mockito.doNothing().when(paymentRequestInfoRedisTemplateWrapper)
                .save(paymentRequestInfoArgumentCaptor.capture());
        Mockito.when(
                nodoOperations.activatePaymentRequest(any(), any(), any(), any(), any(), any(), eq(null), any())
        )
                .thenReturn(Mono.just(paymentRequestInfoAfterActivation));
        Mockito.when(
                jwtTokenUtils.generateToken(
                        eq(jwtSecretKey),
                        eq(tokenValidityTimeInSeconds),
                        eq(new Claims(transactionId, null, null, userId))
                )
        )
                .thenReturn(Either.right("authToken"));
        Mockito.when(
                transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        /* run test */
        Tuple2<Mono<BaseTransactionEvent<?>>, String> response = handler
                .handle(command).block();

        /* asserts */
        TransactionActivatedEvent event = (TransactionActivatedEvent) response.getT1().block();
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(1)).findById(rptId.value());
        assertNotNull(event.getTransactionId());
        assertNotNull(event.getEventCode());
        assertNotNull(event.getCreationDate());
        assertNotNull(event.getId());
        assertEquals(paymentTokenTimeout, event.getData().getPaymentTokenValiditySeconds());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
        assertEquals(null, paymentRequestInfoArgumentCaptor.getValue().dueDate());
    }

    @Test
    void shouldHandleCommandWithoutCachedPaymentRequest() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();
        PaymentNotice paymentNotice = transactionActivatedEvent.getData().getPaymentNotices().get(0);
        TransactionId transactionId = new TransactionId(TRANSACTION_ID);
        RptId rptId = new RptId(paymentNotice.getRptId());
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paName = "paName";
        String paTaxcode = rptId.getFiscalCode();

        PaymentNoticeInfoDto paymentNoticeInfoDto = new PaymentNoticeInfoDto()
                .rptId(rptId.value())
                .amount(paymentNotice.getAmount());

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(paymentNoticeInfoDto)
                .email(EMAIL_STRING);

        TransactionActivateCommand command = new TransactionActivateCommand(
                List.of(rptId),
                new NewTransactionRequestData(
                        requestDto.getIdCart(),
                        confidentialDataManager.encrypt(new Email(requestDto.getEmail())),
                        null,
                        null,
                        requestDto.getPaymentNotices().stream().map(
                                el -> new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                        null,
                                        new RptId(el.getRptId()),
                                        new TransactionAmount(el.getAmount()),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null
                                )
                        ).toList()
                ),
                Transaction.ClientId.CHECKOUT.name(),
                transactionId,
                userId
        );

        PaymentRequestInfo paymentRequestInfoActivation = new PaymentRequestInfo(
                rptId,
                paTaxcode,
                paName,
                paymentNotice.getDescription(),
                paymentNotice.getAmount(),
                dueDate,
                paymentNotice.getPaymentToken(),
                ZonedDateTime.now().toString(),
                idempotencyKey,
                List.of(new PaymentTransferInfo(rptId.getFiscalCode(), false, paymentNotice.getAmount(), null)),
                false,
                null
        );

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.empty());

        Mockito.doNothing().when(paymentRequestInfoRedisTemplateWrapper)
                .save(paymentRequestInfoArgumentCaptor.capture());
        Mockito.when(
                nodoOperations.activatePaymentRequest(any(), any(), any(), any(), any(), any(), eq(null), any())
        )
                .thenReturn(Mono.just(paymentRequestInfoActivation));
        Mockito.when(
                nodoOperations.getEcommerceFiscalCode()
        )
                .thenReturn("77700000000");
        Mockito.when(
                nodoOperations.generateRandomStringToIdempotencyKey()
        )
                .thenReturn("aabbccddee");
        Mockito.when(
                jwtTokenUtils.generateToken(
                        eq(jwtSecretKey),
                        eq(tokenValidityTimeInSeconds),
                        eq(new Claims(transactionId, null, null, userId))
                )
        )
                .thenReturn(Either.right("authToken"));
        Mockito.when(
                transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        /* run test */
        Tuple2<Mono<BaseTransactionEvent<?>>, String> response = handler
                .handle(command).block();

        /* asserts */
        TransactionActivatedEvent event = (TransactionActivatedEvent) response.getT1().block();
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(1)).findById(rptId.value());
        assertNotNull(event.getTransactionId());
        assertNotNull(event.getEventCode());
        assertNotNull(event.getCreationDate());
        assertNotNull(event.getId());
        assertEquals(paymentTokenTimeout, event.getData().getPaymentTokenValiditySeconds());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
        assertEquals(dueDate, paymentRequestInfoArgumentCaptor.getValue().dueDate());
    }

    @Test
    void shouldActivatePaymentRequestSavingCreditorReferenceId() {
        final var creditorReferenceId = UUID.randomUUID().toString();
        final var transactionActivatedEvent = transactionActivateEvent();
        final var paymentNotice = transactionActivatedEvent.getData().getPaymentNotices().get(0);
        final var transactionId = new TransactionId(TRANSACTION_ID);
        final var rptId = new RptId(paymentNotice.getRptId());
        final var idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paName = "paName";
        String paTaxcode = rptId.getFiscalCode();

        PaymentNoticeInfoDto paymentNoticeInfoDto = new PaymentNoticeInfoDto()
                .rptId(rptId.value())
                .amount(paymentNotice.getAmount());

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(paymentNoticeInfoDto)
                .email(EMAIL_STRING);

        TransactionActivateCommand command = new TransactionActivateCommand(
                List.of(rptId),
                new NewTransactionRequestData(
                        requestDto.getIdCart(),
                        confidentialDataManager.encrypt(new Email(requestDto.getEmail())),
                        null,
                        null,
                        requestDto.getPaymentNotices().stream().map(
                                el -> new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                        null,
                                        new RptId(el.getRptId()),
                                        new TransactionAmount(el.getAmount()),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null
                                )
                        ).toList()
                ),
                Transaction.ClientId.CHECKOUT.name(),
                transactionId,
                userId
        );

        final var nodoActivateResponse = new PaymentRequestInfo(
                rptId,
                paTaxcode,
                paName,
                paymentNotice.getDescription(),
                paymentNotice.getAmount(),
                dueDate,
                paymentNotice.getPaymentToken(),
                ZonedDateTime.now().toString(),
                idempotencyKey,
                List.of(new PaymentTransferInfo(rptId.getFiscalCode(), false, paymentNotice.getAmount(), null)),
                false,
                creditorReferenceId
        );

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.empty());
        Mockito.doNothing().when(paymentRequestInfoRedisTemplateWrapper)
                .save(paymentRequestInfoArgumentCaptor.capture());
        Mockito.when(
                nodoOperations.activatePaymentRequest(any(), any(), any(), any(), any(), any(), eq(null), any())
        )
                .thenReturn(Mono.just(nodoActivateResponse));
        Mockito.when(
                nodoOperations.getEcommerceFiscalCode()
        )
                .thenReturn("77700000000");
        Mockito.when(
                nodoOperations.generateRandomStringToIdempotencyKey()
        )
                .thenReturn("aabbccddee");
        Mockito.when(
                jwtTokenUtils.generateToken(
                        eq(jwtSecretKey),
                        eq(tokenValidityTimeInSeconds),
                        eq(new Claims(transactionId, null, null, userId))
                )
        )
                .thenReturn(Either.right("authToken"));
        Mockito.when(
                transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        /* run test */
        Tuple2<Mono<BaseTransactionEvent<?>>, String> response = handler
                .handle(command).block();

        /* asserts */
        TransactionActivatedEvent event = (TransactionActivatedEvent) response.getT1().block();
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(1)).findById(rptId.value());
        assertNotNull(event.getTransactionId());
        assertNotNull(event.getEventCode());
        assertNotNull(event.getCreationDate());
        assertNotNull(event.getId());
        assertEquals(paymentTokenTimeout, event.getData().getPaymentTokenValiditySeconds());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
        assertEquals(dueDate, paymentRequestInfoArgumentCaptor.getValue().dueDate());
        assertTrue(
                event.getData().getPaymentNotices().stream()
                        .anyMatch(it -> it.getCreditorReferenceId().equals(creditorReferenceId))
        );
    }
}
