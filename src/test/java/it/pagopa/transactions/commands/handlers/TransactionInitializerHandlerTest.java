package it.pagopa.transactions.commands.handlers;

import io.opentelemetry.api.common.AttributeKey;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.queues.TracingUtilsTests;
import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.generated.transactions.server.model.PaymentInfoDto;
import it.pagopa.generated.transactions.server.model.PaymentNoticeInfoDto;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.exceptions.InvalidNodoResponseException;
import it.pagopa.transactions.exceptions.JWTTokenGenerationException;
import it.pagopa.transactions.projections.TransactionsProjection;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class TransactionInitializerHandlerTest {

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
            openTelemetryUtils
    );

    @Test
    void shouldHandleCommandForNM3CachedPaymentRequest() {
        Duration elapsedTimeFromActivation = Duration.ofSeconds(paymentTokenTimeout);
        ZonedDateTime transactionActivatedTime = ZonedDateTime.now().minus(elapsedTimeFromActivation);
        RptId rptId = new RptId(RPT_ID);
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String transactionId = UUID.randomUUID().toString();
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
                rptId,
                requestDto,
                Transaction.ClientId.CHECKOUT
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
                false
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent();
        transactionActivatedEvent.setTransactionId(transactionId);
        transactionActivatedEvent.setEventCode(TransactionEventCode.TRANSACTION_ACTIVATED_EVENT);
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
                                false
                        )
                )
        );
        transactionActivatedEvent.setData(transactionActivatedData);

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.of(paymentRequestInfoCached));
        Mockito.when(transactionEventActivatedStoreRepository.save(any()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
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
        Mockito.when(jwtTokenUtils.generateToken(any()))
                .thenReturn(Mono.just("authToken"));

        Mockito.when(confidentialMailUtils.toConfidential(EMAIL_STRING)).thenReturn(Mono.just(EMAIL));

        /* run test */
        Tuple2<Mono<TransactionActivatedEvent>, String> response = handler
                .handle(Tuples.of(command, new TransactionId(TRANSACTION_ID))).block();

        /* asserts */
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(1)).findById(rptId.value());
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(0)).save(any());
        Mockito.verify(openTelemetryUtils, Mockito.times(1)).addSpanWithAttributes(
                eq(OpenTelemetryUtils.REPEATED_ACTIVATION_SPAN_NAME),
                argThat(
                        arguments -> {
                            String spanPaymentToken = arguments.get(
                                    AttributeKey.stringKey(
                                            OpenTelemetryUtils.REPEATED_ACTIVATION_PAYMENT_TOKEN_ATTRIBUTE_KEY
                                    )
                            );
                            Long spanLeftTime = arguments.get(
                                    AttributeKey.longKey(
                                            OpenTelemetryUtils.REPEATED_ACTIVATION_PAYMENT_TOKEN_LEFT_TIME_ATTRIBUTE_KEY
                                    )
                            );
                            return paymentToken.equals(spanPaymentToken) && spanLeftTime != null;
                        }
                )
        );
        Mockito.verify(openTelemetryUtils, Mockito.times(0)).addErrorSpanWithException(any(), any());
        assertNotNull(paymentRequestInfoCached.id());
        TransactionActivatedEvent event = response.getT1().block();

        assertNotNull(event.getTransactionId());
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
        String transactionId = UUID.randomUUID().toString();
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
                rptId,
                requestDto,
                Transaction.ClientId.CHECKOUT
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
                false
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent();
        transactionActivatedEvent.setTransactionId(transactionId);
        transactionActivatedEvent.setEventCode(TransactionEventCode.TRANSACTION_ACTIVATED_EVENT);
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
                                false
                        )
                )
        );
        transactionActivatedEvent.setData(transactionActivatedData);

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.of(paymentRequestInfoCached));
        Mockito.when(transactionEventActivatedStoreRepository.save(any()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        Mockito.when(jwtTokenUtils.generateToken(any()))
                .thenReturn(Mono.just("authToken"));

        Mockito.when(confidentialMailUtils.toConfidential(EMAIL_STRING)).thenReturn(Mono.just(EMAIL));

        /* run test */
        Tuple2<Mono<TransactionActivatedEvent>, String> response = handler
                .handle(Tuples.of(command, new TransactionId(TRANSACTION_ID))).block();

        /* asserts */
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(1)).findById(rptId.value());
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(0)).save(any());
        Mockito.verify(openTelemetryUtils, Mockito.times(0)).addSpanWithAttributes(any(), any());
        Mockito.verify(openTelemetryUtils, Mockito.times(1)).addErrorSpanWithException(
                eq(OpenTelemetryUtils.REPEATED_ACTIVATION_SPAN_NAME),
                argThat(throwable -> throwable.getMessage().contains(rptId.value()))
        );
        assertNotNull(paymentRequestInfoCached.id());
        TransactionActivatedEvent event = response.getT1().block();

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

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        PaymentNoticeInfoDto paymentNoticeInfoDto = new PaymentNoticeInfoDto();
        requestDto.addPaymentNoticesItem(paymentNoticeInfoDto);
        paymentNoticeInfoDto.setRptId(rptId.value());
        requestDto.setEmail("jhon.doe@email.com");
        paymentNoticeInfoDto.setAmount(1200);
        TransactionActivateCommand command = new TransactionActivateCommand(
                rptId,
                requestDto,
                Transaction.ClientId.CHECKOUT
        );

        /* preconditions */

        Mockito.when(jwtTokenUtils.generateToken(any()))
                .thenReturn(Mono.error(new JWTTokenGenerationException()));

        /* run test */
        StepVerifier
                .create(handler.handle(Tuples.of(command, new TransactionId(TRANSACTION_ID))))
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

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        PaymentNoticeInfoDto paymentNoticeInfoDto = new PaymentNoticeInfoDto();
        requestDto.addPaymentNoticesItem(paymentNoticeInfoDto);
        paymentNoticeInfoDto.setRptId(rptId.value());
        requestDto.setEmail("jhon.doe@email.com");
        paymentNoticeInfoDto.setAmount(1200);
        TransactionActivateCommand command = new TransactionActivateCommand(
                rptId,
                requestDto,
                Transaction.ClientId.CHECKOUT
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
                false
        );

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.of(paymentRequestInfoCached));
        Mockito.when(nodoOperations.activatePaymentRequest(any(), any(), any(), any(), any(), any(), eq(dueDate)))
                .thenReturn(Mono.error(new InvalidNodoResponseException("Invalid payment token received")));

        /* run test */
        Mono<Tuple2<Mono<TransactionActivatedEvent>, String>> response = handler
                .handle(Tuples.of(command, new TransactionId(TRANSACTION_ID)));
        /* Assertions */
        InvalidNodoResponseException exception = assertThrows(InvalidNodoResponseException.class, response::block);
        assertEquals("Invalid payment token received", exception.getErrorDescription());

    }

    @Test
    void shouldHandleCommandForOnlyIdempotencyKeyCachedPaymentRequest() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();
        PaymentNotice paymentNotice = transactionActivatedEvent.getData().getPaymentNotices().get(0);

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
                rptId,
                requestDto,
                Transaction.ClientId.CHECKOUT
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
                false
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
                false
        );

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.of(paymentRequestInfoBeforeActivation));
        Mockito.when(transactionEventActivatedStoreRepository.save(any()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.doNothing().when(paymentRequestInfoRedisTemplateWrapper)
                .save(paymentRequestInfoArgumentCaptor.capture());
        Mockito.when(
                nodoOperations.activatePaymentRequest(any(), any(), any(), any(), any(), any(), eq(dueDate))
        )
                .thenReturn(Mono.just(paymentRequestInfoAfterActivation));
        Mockito.when(jwtTokenUtils.generateToken(any()))
                .thenReturn(Mono.just("authToken"));
        Mockito.when(
                transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                        any(),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        Mockito.when(confidentialMailUtils.toConfidential(EMAIL_STRING)).thenReturn(Mono.just(EMAIL));

        /* run test */
        Tuple2<Mono<TransactionActivatedEvent>, String> response = handler
                .handle(Tuples.of(command, new TransactionId(TRANSACTION_ID))).block();

        /* asserts */
        TransactionActivatedEvent event = response.getT1().block();
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
                rptId,
                requestDto,
                Transaction.ClientId.CHECKOUT
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
                false
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
                false
        );

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.of(paymentRequestInfoBeforeActivation));
        Mockito.when(transactionEventActivatedStoreRepository.save(any()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.doNothing().when(paymentRequestInfoRedisTemplateWrapper)
                .save(paymentRequestInfoArgumentCaptor.capture());
        Mockito.when(
                nodoOperations.activatePaymentRequest(any(), any(), any(), any(), any(), any(), eq(null))
        )
                .thenReturn(Mono.just(paymentRequestInfoAfterActivation));
        Mockito.when(jwtTokenUtils.generateToken(any()))
                .thenReturn(Mono.just("authToken"));
        Mockito.when(
                transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        Mockito.when(confidentialMailUtils.toConfidential(EMAIL_STRING)).thenReturn(Mono.just(EMAIL));

        /* run test */
        Tuple2<Mono<TransactionActivatedEvent>, String> response = handler
                .handle(Tuples.of(command, new TransactionId(TRANSACTION_ID))).block();

        /* asserts */
        TransactionActivatedEvent event = response.getT1().block();
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
                rptId,
                requestDto,
                Transaction.ClientId.CHECKOUT
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
                false
        );

        /* preconditions */
        Mockito.when(paymentRequestInfoRedisTemplateWrapper.findById(rptId.value()))
                .thenReturn(Optional.empty());
        Mockito.when(transactionEventActivatedStoreRepository.save(any()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.doNothing().when(paymentRequestInfoRedisTemplateWrapper)
                .save(paymentRequestInfoArgumentCaptor.capture());
        Mockito.when(
                nodoOperations.activatePaymentRequest(any(), any(), any(), any(), any(), any(), eq(null))
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
        Mockito.when(jwtTokenUtils.generateToken(any()))
                .thenReturn(Mono.just("authToken"));
        Mockito.when(
                transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        Mockito.when(confidentialMailUtils.toConfidential(EMAIL_STRING)).thenReturn(Mono.just(EMAIL));

        /* run test */
        Tuple2<Mono<TransactionActivatedEvent>, String> response = handler
                .handle(Tuples.of(command, new TransactionId(TRANSACTION_ID))).block();

        /* asserts */
        TransactionActivatedEvent event = response.getT1().block();
        Mockito.verify(paymentRequestInfoRedisTemplateWrapper, Mockito.times(1)).findById(rptId.value());
        assertNotNull(event.getTransactionId());
        assertNotNull(event.getEventCode());
        assertNotNull(event.getCreationDate());
        assertNotNull(event.getId());
        assertEquals(paymentTokenTimeout, event.getData().getPaymentTokenValiditySeconds());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
        assertEquals(dueDate, paymentRequestInfoArgumentCaptor.getValue().dueDate());
    }

}
