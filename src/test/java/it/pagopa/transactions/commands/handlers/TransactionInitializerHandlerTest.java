package it.pagopa.transactions.commands.handlers;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.models.SendMessageResult;
import it.pagopa.ecommerce.commons.documents.v1.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.v1.IdempotencyKey;
import it.pagopa.ecommerce.commons.domain.v1.RptId;
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestsInfoRepository;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.generated.transactions.server.model.PaymentInfoDto;
import it.pagopa.generated.transactions.server.model.PaymentNoticeInfoDto;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.exceptions.InvalidNodoResponseException;
import it.pagopa.transactions.exceptions.JWTTokenGenerationException;
import it.pagopa.transactions.projections.TransactionsProjection;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.JwtTokenUtils;
import it.pagopa.transactions.utils.NodoOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.transactionActivateEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class TransactionInitializerHandlerTest {

    private final PaymentRequestsInfoRepository paymentRequestInfoRepository = Mockito
            .mock(PaymentRequestsInfoRepository.class);

    private final TransactionsEventStoreRepository<TransactionActivatedData> transactionEventActivatedStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final NodoOperations nodoOperations = Mockito.mock(NodoOperations.class);

    private final QueueAsyncClient transactionClosureSentEventQueueClient = Mockito.mock(QueueAsyncClient.class);

    private final JwtTokenUtils jwtTokenUtils = Mockito.mock(JwtTokenUtils.class);

    private final ConfidentialMailUtils confidentialMailUtils = new ConfidentialMailUtils(
            TransactionTestUtils.confidentialDataManager
    );

    private final TransactionActivateHandler handler = new TransactionActivateHandler(
            paymentRequestInfoRepository,
            eventStoreRepository,
            transactionEventActivatedStoreRepository,
            nodoOperations,
            jwtTokenUtils,
            transactionClosureSentEventQueueClient,
            120,
            confidentialMailUtils
    );

    @Test
    void shouldHandleCommandForNM3CachedPaymentRequest() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String transactionId = UUID.randomUUID().toString();
        String paymentToken = UUID.randomUUID().toString();
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
                null,
                paymentToken,
                idempotencyKey
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
                                null
                        )
                )
        );
        transactionActivatedEvent.setData(transactionActivatedData);

        /* preconditions */
        Mockito.when(paymentRequestInfoRepository.findById(rptId))
                .thenReturn(Optional.of(paymentRequestInfoCached));
        Mockito.when(transactionEventActivatedStoreRepository.save(any()))
                .thenReturn(Mono.just(transactionActivatedEvent));
        Mockito.when(paymentRequestInfoRepository.save(any(PaymentRequestInfo.class)))
                .thenReturn(paymentRequestInfoCached);
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(
                        any(BinaryData.class),
                        any(),
                        any()
                )
        )
                .thenReturn(queueSuccessfulResponse());
        Mockito.when(jwtTokenUtils.generateToken(any()))
                .thenReturn(Mono.just("authToken"));
        ReflectionTestUtils.setField(handler, "nodoParallelRequests", 5);
        /* run test */
        Tuple2<Mono<TransactionActivatedEvent>, String> response = handler
                .handle(command).block();

        /* asserts */
        Mockito.verify(paymentRequestInfoRepository, Mockito.times(1)).findById(rptId);
        assertNotNull(paymentRequestInfoCached.id());
    }

    @Test
    void shouldFailForTokenGenerationError() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String transactionId = UUID.randomUUID().toString();
        String paymentToken = UUID.randomUUID().toString();
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
                null,
                paymentToken,
                idempotencyKey
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
                                null
                        )
                )
        );
        transactionActivatedEvent.setData(transactionActivatedData);

        /* preconditions */
        Mockito.when(paymentRequestInfoRepository.findById(rptId))
                .thenReturn(Optional.of(paymentRequestInfoCached));
        Mockito.when(transactionEventActivatedStoreRepository.save(any()))
                .thenReturn(Mono.just(transactionActivatedEvent));
        Mockito.when(paymentRequestInfoRepository.save(any(PaymentRequestInfo.class)))
                .thenReturn(paymentRequestInfoCached);
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(
                        any(BinaryData.class),
                        any(),
                        any()
                )
        )
                .thenReturn(queueSuccessfulResponse());
        Mockito.when(jwtTokenUtils.generateToken(any()))
                .thenReturn(Mono.error(new JWTTokenGenerationException()));
        ReflectionTestUtils.setField(handler, "nodoParallelRequests", 5);
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
                null,
                null,
                idempotencyKey
        );

        /* preconditions */
        Mockito.when(paymentRequestInfoRepository.findById(rptId))
                .thenReturn(Optional.of(paymentRequestInfoCached));
        Mockito.when(nodoOperations.activatePaymentRequest(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new InvalidNodoResponseException("Invalid payment token received")));
        ReflectionTestUtils.setField(handler, "nodoParallelRequests", 5);
        /* run test */
        Mono<Tuple2<Mono<TransactionActivatedEvent>, String>> response = handler
                .handle(command);
        /* Assertions */
        InvalidNodoResponseException exception = assertThrows(InvalidNodoResponseException.class, response::block);
        assertEquals("Invalid payment token received", exception.getErrorDescription());

    }

    private Mono<Response<SendMessageResult>> queueSuccessfulResponse() {
        return Mono.just(
                new Response<>() {
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
                }
        );
    }

    @Test
    void shouldHandleCommandForOnlyIdempotencyKeyCachedPaymentRequest()
            throws InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException,
            NoSuchAlgorithmException, BadPaddingException, InvalidKeyException {
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
                .email(
                        TransactionTestUtils.confidentialDataManager
                                .decrypt(transactionActivatedEvent.getData().getEmail())
                );

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
                idempotencyKey
        );

        PaymentRequestInfo paymentRequestInfoAfterActivation = new PaymentRequestInfo(
                rptId,
                paTaxcode,
                paName,
                paymentNotice.getDescription(),
                paymentNotice.getAmount(),
                null,
                paymentNotice.getPaymentToken(),
                idempotencyKey
        );

        /* preconditions */
        Mockito.when(paymentRequestInfoRepository.findById(rptId))
                .thenReturn(Optional.of(paymentRequestInfoBeforeActivation));
        Mockito.when(transactionEventActivatedStoreRepository.save(any()))
                .thenReturn(Mono.just(transactionActivatedEvent));
        Mockito.when(paymentRequestInfoRepository.save(any(PaymentRequestInfo.class)))
                .thenReturn(paymentRequestInfoBeforeActivation);
        Mockito.when(
                nodoOperations.activatePaymentRequest(any(), any(), any(), any())
        )
                .thenReturn(Mono.just(paymentRequestInfoAfterActivation));
        Mockito.when(jwtTokenUtils.generateToken(any()))
                .thenReturn(Mono.just("authToken"));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(
                        any(BinaryData.class),
                        any(),
                        any()
                )
        )
                .thenReturn(queueSuccessfulResponse());

        ReflectionTestUtils.setField(handler, "nodoParallelRequests", 5);
        /* run test */
        Tuple2<Mono<TransactionActivatedEvent>, String> response = handler
                .handle(command).block();

        /* asserts */
        TransactionActivatedEvent event = response.getT1().block();
        Mockito.verify(paymentRequestInfoRepository, Mockito.times(1)).findById(rptId);
        assertNotNull(event.getTransactionId());
        assertNotNull(event.getEventCode());
        assertNotNull(event.getCreationDate());
        assertNotNull(event.getId());
    }

    @Test
    void shouldHandleCommandWithoutCachedPaymentRequest()
            throws InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException,
            NoSuchAlgorithmException, BadPaddingException, InvalidKeyException {
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
                .email(
                        TransactionTestUtils.confidentialDataManager
                                .decrypt(transactionActivatedEvent.getData().getEmail())
                );

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
                null,
                paymentNotice.getPaymentToken(),
                idempotencyKey
        );

        /* preconditions */
        Mockito.when(paymentRequestInfoRepository.findById(rptId))
                .thenReturn(Optional.empty());
        Mockito.when(transactionEventActivatedStoreRepository.save(any()))
                .thenReturn(Mono.just(transactionActivatedEvent));
        Mockito.when(paymentRequestInfoRepository.save(any(PaymentRequestInfo.class)))
                .thenReturn(paymentRequestInfoActivation);
        Mockito.when(
                nodoOperations.activatePaymentRequest(any(), any(), any(), any())
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
                transactionClosureSentEventQueueClient.sendMessageWithResponse(
                        any(BinaryData.class),
                        any(),
                        any()
                )
        )
                .thenReturn(queueSuccessfulResponse());

        ReflectionTestUtils.setField(handler, "nodoParallelRequests", 5);
        /* run test */
        Tuple2<Mono<TransactionActivatedEvent>, String> response = handler
                .handle(command).block();

        /* asserts */
        TransactionActivatedEvent event = response.getT1().block();
        Mockito.verify(paymentRequestInfoRepository, Mockito.times(1)).findById(rptId);
        assertNotNull(event.getTransactionId());
        assertNotNull(event.getEventCode());
        assertNotNull(event.getCreationDate());
        assertNotNull(event.getId());
    }
}
