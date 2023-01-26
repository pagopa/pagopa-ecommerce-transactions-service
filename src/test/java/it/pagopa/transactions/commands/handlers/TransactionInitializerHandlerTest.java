package it.pagopa.transactions.commands.handlers;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.models.SendMessageResult;
import it.pagopa.ecommerce.commons.documents.*;
import it.pagopa.ecommerce.commons.domain.IdempotencyKey;
import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.domain.TransactionEventCode;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestsInfoRepository;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.generated.transactions.server.model.PaymentInfoDto;
import it.pagopa.generated.transactions.server.model.PaymentNoticeInfoDto;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.exceptions.JWTTokenGenerationException;
import it.pagopa.transactions.projections.TransactionsProjection;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.JwtTokenUtils;
import it.pagopa.transactions.utils.NodoOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple3;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class TransactionInitializerHandlerTest {

    private final PaymentRequestsInfoRepository paymentRequestInfoRepository = Mockito
            .mock(PaymentRequestsInfoRepository.class);

    private final TransactionsEventStoreRepository<TransactionActivatedData> transactionEventActivatedStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsEventStoreRepository<TransactionActivationRequestedData> transactionEventActivationRequestedStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final NodoOperations nodoOperations = Mockito.mock(NodoOperations.class);

    private final QueueAsyncClient transactionClosureSentEventQueueClient = Mockito.mock(QueueAsyncClient.class);

    private final JwtTokenUtils jwtTokenUtils = Mockito.mock(JwtTokenUtils.class);

    private final TransactionActivateHandler handler = new TransactionActivateHandler(
            paymentRequestInfoRepository,
            transactionEventActivatedStoreRepository,
            transactionEventActivationRequestedStoreRepository,
            nodoOperations,
            jwtTokenUtils,
            transactionClosureSentEventQueueClient,
            120
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
        Integer amount = Integer.valueOf(1000);

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        PaymentNoticeInfoDto paymentNoticeInfoDto = new PaymentNoticeInfoDto();
        requestDto.addPaymentNoticesItem(paymentNoticeInfoDto);
        paymentNoticeInfoDto.setRptId(rptId.value());
        requestDto.setEmail("jhon.doe@email.com");
        paymentNoticeInfoDto.setAmount(1200);
        paymentNoticeInfoDto.setPaymentContextCode(UUID.randomUUID().toString().replace("-", ""));
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
                true,
                paymentToken,
                idempotencyKey
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent();
        transactionActivatedEvent.setTransactionId(transactionId);
        transactionActivatedEvent.setEventCode(TransactionEventCode.TRANSACTION_ACTIVATED_EVENT);
        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setPaymentNotices(
                Arrays.asList(
                        new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                                paymentToken,
                                rptId.value(),
                                null,
                                null,
                                null
                        )
                )
        );
        transactionActivatedEvent.setData(transactionActivatedData);

        /** preconditions */
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
        /** run test */
        Tuple3<Mono<TransactionActivatedEvent>, Mono<TransactionActivationRequestedEvent>, String> response = handler
                .handle(command).block();

        /** asserts */
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
        Integer amount = Integer.valueOf(1000);

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        PaymentNoticeInfoDto paymentNoticeInfoDto = new PaymentNoticeInfoDto();
        requestDto.addPaymentNoticesItem(paymentNoticeInfoDto);
        paymentNoticeInfoDto.setRptId(rptId.value());
        requestDto.setEmail("jhon.doe@email.com");
        paymentNoticeInfoDto.setAmount(1200);
        paymentNoticeInfoDto.setPaymentContextCode(UUID.randomUUID().toString().replace("-", ""));
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
                true,
                paymentToken,
                idempotencyKey
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent();
        transactionActivatedEvent.setTransactionId(transactionId);
        transactionActivatedEvent.setEventCode(TransactionEventCode.TRANSACTION_ACTIVATED_EVENT);
        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setPaymentNotices(
                Arrays.asList(
                        new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                                paymentToken,
                                rptId.value(),
                                null,
                                null,
                                null
                        )
                )
        );
        transactionActivatedEvent.setData(transactionActivatedData);

        /** preconditions */
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
        /** run test */
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

        assertFalse(transactionsProjection.equals(differentTransactionsProjection));
        assertEquals(
                Boolean.TRUE,
                transactionsProjection.getData().equals(differentTransactionsProjection.getData())
        );
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
}
