package it.pagopa.transactions.services.v2;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionView;
import it.pagopa.ecommerce.commons.documents.v2.*;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.domain.v2.*;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.*;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.redis.templatewrappers.ExclusiveLockDocumentWrapper;
import it.pagopa.ecommerce.commons.redis.templatewrappers.UniqueIdTemplateWrapper;
import it.pagopa.ecommerce.commons.redis.templatewrappers.v2.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.PaymentMethodResponseDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.PaymentMethodStatusDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.RangeDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.SessionPaymentMethodResponseDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.BundleDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeRequestDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeResponseDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.client.WalletClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.TransactionUserCancelCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.NotImplementedException;
import it.pagopa.transactions.exceptions.PaymentNoticeAllCCPMismatchException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.data.redis.AutoConfigureDataRedis;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import reactor.util.function.Tuples;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent;

@WebFluxTest
@TestPropertySource(locations = "classpath:application-tests.properties")
@Import(
    {
            it.pagopa.transactions.services.v1.TransactionsService.class,
            it.pagopa.transactions.services.v2.TransactionsService.class,
            it.pagopa.transactions.services.v2_1.TransactionsService.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler.class,
            it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler.class,
            it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureRequestHandler.class,
            it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler.class,
            it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler.class,
            it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler.class,
            it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler.class,
            TransactionsEventStoreRepository.class,
            UUIDUtils.class
    }
)
@AutoConfigureDataRedis
class TransactionServiceTests {
    @MockitoBean
    private TransactionsViewRepository repository;

    @Autowired
    @Qualifier(it.pagopa.transactions.services.v1.TransactionsService.QUALIFIER_NAME)
    private it.pagopa.transactions.services.v1.TransactionsService transactionsServiceV1;

    @Autowired
    private UUIDUtils uuidUtils;

    @MockitoBean
    private EcommercePaymentMethodsClient ecommercePaymentMethodsClient;

    @MockitoBean
    private WalletClient walletClient;

    @MockitoBean
    private PaymentGatewayClient paymentGatewayClient;

    @MockitoBean
    private NodeForPspClient nodeForPspClient;

    @MockitoBean
    @Qualifier("transactionClosureRetryQueueAsyncClientV1")
    private QueueAsyncClient queueAsyncClientClosureRetryV1;

    @MockitoBean
    @Qualifier("transactionRefundQueueAsyncClientV1")
    private QueueAsyncClient queueAsyncClientRefundV1;

    @MockitoBean
    @Qualifier("transactionClosureRetryQueueAsyncClientV2")
    private QueueAsyncClient queueAsyncClientClosureRetryV2;

    @MockitoBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler transactionActivateHandlerV2;

    @MockitoBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler transactionCancelHandlerV2;

    @MockitoBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandlerV2;

    @MockitoBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV2;

    @MockitoBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler transactionUpdateStatusHandlerV2;

    @MockitoBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureRequestHandler transactionSendClosureRequestHandler;

    @MockitoBean
    private it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV2;

    @MockitoBean
    private it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV2;

    @MockitoBean
    private it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler closureRequestedProjectionHandler;

    @MockitoBean
    private TransactionsEventStoreRepository transactionsEventStoreRepository;

    @MockitoBean
    private TransactionsActivationProjectionHandler transactionsActivationProjectionHandler;

    @MockitoBean
    private it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV2;

    @Captor
    private ArgumentCaptor<TransactionRequestAuthorizationCommand> commandArgumentCaptor;

    @MockitoBean
    private TransactionsUtils transactionsUtils;

    @MockitoBean
    private AuthRequestDataUtils authRequestDataUtils;

    @MockitoBean
    private TracingUtils tracingUtils;

    @MockitoBean
    private PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper;

    @MockitoBean
    private UniqueIdTemplateWrapper uniqueIdTemplateWrapper;

    @MockitoBean
    private UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils;

    @MockitoBean
    private OpenTelemetryUtils openTelemetryUtils;

    @MockitoBean
    private ConfidentialMailUtils confidentialMailUtils;

    @MockitoBean
    private ExclusiveLockDocumentWrapper exclusiveLockDocumentWrapper;

    final String TRANSACTION_ID = TransactionTestUtils.TRANSACTION_ID;
    final String USER_ID = TransactionTestUtils.USER_ID;

    private static final String expectedOperationTimestamp = "2023-01-01T01:02:03";

    @Test
    void getTransactionReturnsTransactionDataOriginProvided() {

        final Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        transaction.setPaymentGateway("VPOS");
        transaction.setSendPaymentResultOutcome(
                TransactionUserReceiptData.Outcome.OK
        );
        transaction.setAuthorizationCode("00");
        transaction.setAuthorizationErrorCode(null);

        final TransactionInfoDto expected = new TransactionInfoDto()
                .transactionId(TRANSACTION_ID)
                .payments(
                        transaction.getPaymentNotices().stream().map(
                                p -> new PaymentInfoDto()
                                        .paymentToken(p.getPaymentToken())
                                        .rptId(p.getRptId())
                                        .reason(p.getDescription())
                                        .amount(p.getAmount())
                                        .isAllCCP(p.isAllCCP())
                                        .transferList(
                                                p.getTransferList().stream().map(
                                                        notice -> new TransferDto()
                                                                .paFiscalCode(notice.getPaFiscalCode())
                                                                .digitalStamp(notice.getDigitalStamp())
                                                                .transferAmount(notice.getTransferAmount())
                                                                .transferCategory(notice.getTransferCategory())
                                                ).toList()
                                        )
                        ).toList()
                )
                .clientId(TransactionInfoDto.ClientIdEnum.CHECKOUT)
                .feeTotal(null)
                .status(TransactionStatusDto.ACTIVATED)
                .idCart("ecIdCart")
                .gateway("VPOS")
                .sendPaymentResultOutcome(TransactionInfoDto.SendPaymentResultOutcomeEnum.OK)
                .authorizationCode("00")
                .errorCode(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        when(transactionsUtils.convertEnumerationV1(any())).thenCallRealMethod();
        assertEquals(
                transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, UUID.fromString(USER_ID)).block(),
                expected
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, UUID.fromString(USER_ID)))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void getTransactionReturnsTransactionV2WithNullUserId() {

        final Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        transaction.setPaymentGateway("VPOS");
        transaction.setSendPaymentResultOutcome(
                TransactionUserReceiptData.Outcome.OK
        );
        transaction.setAuthorizationCode("00");
        transaction.setAuthorizationErrorCode(null);
        transaction.setUserId(null);

        final TransactionInfoDto expected = new TransactionInfoDto()
                .transactionId(TRANSACTION_ID)
                .payments(
                        transaction.getPaymentNotices().stream().map(
                                p -> new PaymentInfoDto()
                                        .paymentToken(p.getPaymentToken())
                                        .rptId(p.getRptId())
                                        .reason(p.getDescription())
                                        .amount(p.getAmount())
                                        .isAllCCP(p.isAllCCP())
                                        .transferList(
                                                p.getTransferList().stream().map(
                                                        notice -> new TransferDto()
                                                                .paFiscalCode(notice.getPaFiscalCode())
                                                                .digitalStamp(notice.getDigitalStamp())
                                                                .transferAmount(notice.getTransferAmount())
                                                                .transferCategory(notice.getTransferCategory())
                                                ).toList()
                                        )
                        ).toList()
                )
                .clientId(TransactionInfoDto.ClientIdEnum.CHECKOUT)
                .feeTotal(null)
                .status(TransactionStatusDto.ACTIVATED)
                .idCart("ecIdCart")
                .gateway("VPOS")
                .sendPaymentResultOutcome(TransactionInfoDto.SendPaymentResultOutcomeEnum.OK)
                .authorizationCode("00")
                .errorCode(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        when(transactionsUtils.convertEnumerationV1(any())).thenCallRealMethod();
        assertEquals(
                transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null).block(),
                expected
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void getTransactionReturnsUnexpectedClassInstance() {
        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(new BaseTransactionView() {
        }));
        StepVerifier
                .create(transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null))
                .expectErrorMatches(NotImplementedException.class::isInstance)
                .verify();
    }

    @Test
    void getTransactionReturnsTransactionDataOriginProvidedNoAdditionalFields() {

        final Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        transaction.setPaymentGateway(null);
        transaction.setSendPaymentResultOutcome(null);
        transaction.setAuthorizationCode(null);
        transaction.setAuthorizationErrorCode(null);

        final TransactionInfoDto expected = new TransactionInfoDto()
                .transactionId(TRANSACTION_ID)
                .payments(
                        transaction.getPaymentNotices().stream().map(
                                p -> new PaymentInfoDto()
                                        .paymentToken(p.getPaymentToken())
                                        .rptId(p.getRptId())
                                        .reason(p.getDescription())
                                        .amount(p.getAmount())
                                        .isAllCCP(p.isAllCCP())
                                        .transferList(
                                                p.getTransferList().stream().map(
                                                        notice -> new TransferDto()
                                                                .paFiscalCode(notice.getPaFiscalCode())
                                                                .digitalStamp(notice.getDigitalStamp())
                                                                .transferAmount(notice.getTransferAmount())
                                                                .transferCategory(notice.getTransferCategory())
                                                ).toList()
                                        )
                        ).toList()
                )
                .clientId(TransactionInfoDto.ClientIdEnum.CHECKOUT)
                .feeTotal(null)
                .status(TransactionStatusDto.ACTIVATED)
                .idCart("ecIdCart")
                .gateway(null)
                .sendPaymentResultOutcome(null)
                .authorizationCode(null)
                .errorCode(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        when(transactionsUtils.convertEnumerationV1(any())).thenCallRealMethod();
        assertEquals(
                transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, UUID.fromString(USER_ID)).block(),
                expected
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, UUID.fromString(USER_ID)))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void getTransactionThrowsOnTransactionNotFound() {
        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.empty());

        assertThrows(
                TransactionNotFoundException.class,
                () -> transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null).block(),
                TRANSACTION_ID
        );
    }

    @Test
    void getPaymentTokenByTransactionNotFound() {

        TransactionNotFoundException exception = new TransactionNotFoundException(TRANSACTION_ID);

        assertEquals(
                exception.getPaymentToken(),
                TRANSACTION_ID
        );
    }

    @Test
    void shouldRedirectToAuthorizationURIForValidRequestWithNPGCardsDetailFor() {
        String orderId = "orderId";
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200)
                .pspId("PSP_CODE")
                .isAllCCP(false)
                .details(
                        new CardsAuthRequestDetailsDto().orderId(orderId)
                );

        Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        /* preconditions */
        CalculateFeeResponseDto calculateFeeResponseDto = new CalculateFeeResponseDto()
                .belowThreshold(true)
                .paymentMethodName("PaymentMethodName")
                .paymentMethodDescription("PaymentMethodDescription")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethods.v2.dto.PaymentMethodStatusDto.ENABLED
                )
                .bundles(
                        List.of(
                                new BundleDto()
                                        .idPsp("PSP_CODE")
                                        .taxPayerFee(200l)
                        )
                );

        PaymentMethodResponseDto paymentMethod = new PaymentMethodResponseDto()
                .name("paymentMethodName")
                .description("desc")
                .status(PaymentMethodStatusDto.ENABLED)
                .id("paymentInstrumentId")
                .paymentTypeCode("PO")
                .addRangesItem(new RangeDto().min(0L).max(100L));

        // TODO Check if this response is ok
        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN)
                .url("http://example.com");

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(stateResponseDto.getUrl());

        Mockito.when(
                ecommercePaymentMethodsClient.calculateFee(
                        eq(authorizationRequest.getPaymentInstrumentId()),
                        eq(transaction.getTransactionId()),
                        any(),
                        eq(Integer.MAX_VALUE)
                )
        ).thenReturn(
                Mono.just(calculateFeeResponseDto)
        );

        Mockito.when(
                ecommercePaymentMethodsClient.getPaymentMethod(eq(authorizationRequest.getPaymentInstrumentId()), any())
        )
                .thenReturn(Mono.just(paymentMethod));

        Mockito.when(
                ecommercePaymentMethodsClient.retrieveCardData(authorizationRequest.getPaymentInstrumentId(), orderId)
        ).thenReturn(
                Mono.just(
                        new SessionPaymentMethodResponseDto().bin("bin").brand("VISA").sessionId("sessionId")
                                .expiringDate("0226").lastFourDigits("1234")
                )
        );

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));
        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(any(), any()))
                .thenReturn(Mono.just(stateResponseDto));

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionsUtils.getPaymentNotices(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getTransactionTotalAmount(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getRptId(any(), anyInt())).thenCallRealMethod();

        Mockito.when(
                transactionRequestAuthorizationHandlerV2
                        .handleWithCreationDate(any(TransactionRequestAuthorizationCommand.class))
        )
                .thenReturn(
                        Mono.just(
                                Tuples.of(
                                        requestAuthorizationResponse,
                                        TransactionTestUtils.transactionAuthorizationRequestedEvent()
                                )
                        )
                );

        /* test */
        StepVerifier
                .create(
                        transactionsServiceV1
                                .requestTransactionAuthorization(
                                        TRANSACTION_ID,
                                        UUID.fromString(USER_ID),
                                        null,
                                        null,
                                        authorizationRequest
                                )
                )
                .expectNext(requestAuthorizationResponse)
                .verifyComplete();

        verify(transactionRequestAuthorizationHandlerV2).handleWithCreationDate(commandArgumentCaptor.capture());

        AuthorizationRequestData captureData = commandArgumentCaptor.getValue().getData();
        assertEquals(calculateFeeResponseDto.getPaymentMethodDescription(), captureData.paymentMethodDescription());
        assertEquals(calculateFeeResponseDto.getPaymentMethodName(), captureData.paymentMethodName());
    }

    @Test
    void shouldReturnNotFoundForNonExistingRequest() {
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(0)
                .paymentInstrumentId("paymentInstrumentId")
                .isAllCCP(false)
                .pspId("pspId");

        /* preconditions */
        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.empty());

        /* test */
        Mono<RequestAuthorizationResponseDto> requestAuthorizationResponseDtoMono = transactionsServiceV1
                .requestTransactionAuthorization(TRANSACTION_ID, null, null, null, authorizationRequest);
        assertThrows(
                TransactionNotFoundException.class,
                requestAuthorizationResponseDtoMono::block
        );
    }

    @Test
    void shouldReturnTransactionInfoForSuccessfulAuthAndClosureRequested() {
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);

        UUID transactionIdDecoded = transactionId.uuid();

        Transaction transactionDocument = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                ZonedDateTime.now()
        );

        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionDocument.getTransactionId()),
                transactionDocument.getPaymentNotices().stream().map(
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
                                                paymentNotice.getAmount(),
                                                null
                                        )
                                ),
                                paymentNotice.isAllCCP(),
                                new CompanyName(paymentNotice.getCompanyName()),
                                null
                        )
                ).toList(),
                transactionDocument.getEmail(),
                "faultCode",
                "faultCodeString",
                Transaction.ClientId.CHECKOUT,
                transactionDocument.getIdCart(),
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new EmptyTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeNpgGatewayDto()
                                .operationResult(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)
                )
                .timestampOperation(OffsetDateTime.now());

        TransactionAuthorizationCompletedData statusUpdateData = new TransactionAuthorizationCompletedData(
                "authorizationCode",
                null,
                expectedOperationTimestamp,
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEndToEndId",
                        "errorCode",
                        "validationServiceId"
                )

        );

        TransactionAuthorizationCompletedEvent event = new TransactionAuthorizationCompletedEvent(
                transactionDocument.getTransactionId(),
                statusUpdateData
        );

        TransactionClosureRequestedEvent closureSentEvent = TransactionTestUtils
                .transactionClosureRequestedEvent();

        TransactionInfoDto expectedResponse = new TransactionInfoDto()
                .transactionId(transactionDocument.getTransactionId())
                .payments(
                        transactionDocument.getPaymentNotices().stream().map(
                                paymentNotice -> new PaymentInfoDto()
                                        .amount(paymentNotice.getAmount())
                                        .reason(paymentNotice.getDescription())
                                        .paymentToken(paymentNotice.getPaymentToken())
                                        .rptId(paymentNotice.getRptId())
                        ).toList()
                )
                .status(TransactionStatusDto.CLOSED);

        Transaction closedTransactionDocument = new Transaction(
                transactionDocument.getTransactionId(),
                transactionDocument.getPaymentNotices(),
                null,
                transactionDocument.getEmail(),
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSED,
                Transaction.ClientId.CHECKOUT,
                ZonedDateTime.now().toString(),
                transactionDocument.getIdCart(),
                transactionDocument.getRrn(),
                transactionDocument.getUserId(),
                transactionDocument.getPaymentTypeCode(),
                transactionDocument.getPspId(),
                transactionDocument.getLastProcessedEventAt()
        );

        /* preconditions */

        Mockito.when(transactionUpdateAuthorizationHandlerV2.handle(any()))
                .thenReturn(Mono.just(event));

        Mockito.when(authorizationUpdateProjectionHandlerV2.handle(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionSendClosureRequestHandler.handle(any()))
                .thenReturn(Mono.just(closureSentEvent));

        Mockito.when(closureRequestedProjectionHandler.handle(any()))
                .thenReturn(Mono.just(closedTransactionDocument));
        Mockito.when(
                transactionsEventStoreRepository.findByTransactionIdAndEventCode(
                        transactionId.value(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                )
        )
                .thenReturn(Mono.empty());
        Mockito.when(transactionsUtils.reduceEvents(any(), any(), any(), any()))
                .thenReturn(Mono.just(new it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction())).thenReturn(
                        Mono.just(
                                TransactionTestUtils.transactionWithRequestedAuthorization(
                                        TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                                        TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString())
                                )
                        )
                );
        Mockito.when(transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        Flux.fromIterable(
                                List.of(
                                        TransactionTestUtils.transactionActivateEvent(),
                                        TransactionTestUtils.transactionAuthorizationRequestedEvent()
                                )
                        )
                );
        when(transactionsUtils.convertEnumerationV1(any())).thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsServiceV1
                .updateTransactionAuthorization(transactionIdDecoded, updateAuthorizationRequest).block();

        assertEquals(expectedResponse, transactionInfoResponse);
    }

    @Test
    void shouldReturnNotFoundExceptionForNonExistingTransactionForTransactionUpdate() {
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);
        UUID transactionIdDecoded = transactionId.uuid();

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeNpgGatewayDto()
                                .operationResult(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)
                )
                .timestampOperation(OffsetDateTime.now());

        /* preconditions */
        Mockito.when(transactionsUtils.reduceEvents(any(), any(), any(), any()))
                .thenReturn(Mono.error(new TransactionNotFoundException("")))
                .thenReturn(Mono.error(new TransactionNotFoundException("")));
        Mockito.when(transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(Flux.empty());
        Mockito.when(transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(Flux.empty());
        Mockito.when(
                transactionsEventStoreRepository.findByTransactionIdAndEventCode(
                        transactionId.value(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                )
        )
                .thenReturn(Mono.empty());
        Hooks.onOperatorDebug();
        /* test */
        StepVerifier
                .create(
                        transactionsServiceV1
                                .updateTransactionAuthorization(transactionIdDecoded, updateAuthorizationRequest)
                )
                .expectErrorMatches(TransactionNotFoundException.class::isInstance)
                .verify();
    }

    @Test
    void shouldReturnTransactionInfoForSuccessfulNotifiedOk() {
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);

        Transaction transactionDocument = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFIED_OK,
                ZonedDateTime.now()
        );

        TransactionUserReceiptRequestedEvent event = new TransactionUserReceiptRequestedEvent(
                transactionDocument.getTransactionId(),
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        );

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        TransactionInfoDto expectedResponse = new TransactionInfoDto()
                .transactionId(transactionDocument.getTransactionId())
                .payments(
                        transactionDocument.getPaymentNotices().stream().map(
                                paymentNotice -> new PaymentInfoDto()
                                        .amount(paymentNotice.getAmount())
                                        .reason(paymentNotice.getDescription())
                                        .paymentToken(paymentNotice.getPaymentToken())
                                        .rptId(paymentNotice.getRptId())
                        ).toList()
                )
                .status(TransactionStatusDto.NOTIFIED_OK);

        /* preconditions */
        Mockito.when(repository.findById(transactionId.value()))
                .thenReturn(Mono.just(transactionDocument));

        Mockito.when(transactionUpdateStatusHandlerV2.handle(any()))
                .thenReturn(Mono.just(event));

        Mockito.when(transactionUserReceiptProjectionHandlerV2.handle(any()))
                .thenReturn(Mono.just(transactionDocument));
        when(transactionsUtils.convertEnumerationV1(any())).thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsServiceV1
                .addUserReceipt(transactionId.value(), addUserReceiptRequest).block();

        assertEquals(expectedResponse, transactionInfoResponse);
    }

    @Test
    void shouldReturnTransactionInfoForSuccessfulNotifiedKo() {
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);

        Transaction transactionDocument = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFIED_KO,
                ZonedDateTime.now()
        );

        TransactionUserReceiptRequestedEvent event = new TransactionUserReceiptRequestedEvent(
                transactionDocument.getTransactionId(),
                TransactionTestUtils.transactionUserReceiptData(
                        (TransactionUserReceiptData.Outcome.KO)
                )
        );

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.KO)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        TransactionInfoDto expectedResponse = new TransactionInfoDto()
                .transactionId(transactionDocument.getTransactionId())
                .payments(
                        transactionDocument.getPaymentNotices().stream().map(
                                paymentNotice -> new PaymentInfoDto()
                                        .amount(paymentNotice.getAmount())
                                        .reason(paymentNotice.getDescription())
                                        .paymentToken(paymentNotice.getPaymentToken())
                                        .rptId(paymentNotice.getRptId())
                        ).toList()
                )
                .status(TransactionStatusDto.NOTIFIED_KO);

        /* preconditions */
        Mockito.when(repository.findById(transactionId.value()))
                .thenReturn(Mono.just(transactionDocument));

        Mockito.when(transactionUpdateStatusHandlerV2.handle(any()))
                .thenReturn(Mono.just(event));

        Mockito.when(transactionUserReceiptProjectionHandlerV2.handle(any()))
                .thenReturn(Mono.just(transactionDocument));
        when(transactionsUtils.convertEnumerationV1(any()))
                .thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsServiceV1
                .addUserReceipt(transactionId.value(), addUserReceiptRequest).block();

        assertEquals(expectedResponse, transactionInfoResponse);
    }

    @Test
    void shouldReturnNotFoundExceptionForNonExistingToAddUserReceipt() {
        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        /* preconditions */
        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.empty());

        /* test */
        StepVerifier.create(transactionsServiceV1.addUserReceipt(TRANSACTION_ID, addUserReceiptRequest))
                .expectErrorMatches(TransactionNotFoundException.class::isInstance)
                .verify();
    }

    @Test
    void shouldReturnBadRequestForMismatchingFlagAllCCP() {
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200)
                .pspId("PSP_CODE")
                .isAllCCP(true);

        Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        /* preconditions */
        CalculateFeeResponseDto calculateFeeResponseDto = new CalculateFeeResponseDto()
                .belowThreshold(true)
                .paymentMethodDescription("PaymentMethodDescription")
                .paymentMethodName("PaymentMethodName")
                .paymentMethodStatus(
                        it.pagopa.generated.ecommerce.paymentmethods.v2.dto.PaymentMethodStatusDto.ENABLED
                )
                .bundles(
                        List.of(
                                new BundleDto()
                                        .idPsp("PSP_CODE")
                                        .taxPayerFee(200l)
                        )
                );

        PaymentMethodResponseDto paymentMethod = new PaymentMethodResponseDto()
                .name("paymentMethodName")
                .description("desc")
                .status(PaymentMethodStatusDto.ENABLED)
                .id("id")
                .paymentTypeCode("PO")
                .addRangesItem(new RangeDto().min(0L).max(100L));

        // TODO: Check if this response is ok
        StateResponseDto stateResponseDto = new StateResponseDto().state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN)
                .url("http://example.com");

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(stateResponseDto.getUrl());

        Mockito.when(ecommercePaymentMethodsClient.calculateFee(any(), any(), any(), any())).thenReturn(
                Mono.just(calculateFeeResponseDto)
        );

        Mockito.when(ecommercePaymentMethodsClient.getPaymentMethod(any(), any())).thenReturn(Mono.just(paymentMethod));

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(any(), any()))
                .thenReturn(Mono.just(stateResponseDto));

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionsUtils.getPaymentNotices(any())).thenCallRealMethod();

        Mockito.when(transactionsUtils.getTransactionTotalAmount(any())).thenCallRealMethod();

        Mockito.when(transactionRequestAuthorizationHandlerV2.handle(any()))
                .thenReturn(Mono.just(requestAuthorizationResponse));

        /* test */
        StepVerifier
                .create(
                        transactionsServiceV1
                                .requestTransactionAuthorization(
                                        TRANSACTION_ID,
                                        UUID.fromString(USER_ID),
                                        null,
                                        null,
                                        authorizationRequest
                                )
                )
                .expectErrorMatches(PaymentNoticeAllCCPMismatchException.class::isInstance)
                .verify();
    }

    @Test
    void shouldThrowsInvalidRequestExceptionForInvalidClientID() {
        Transaction.ClientId clientId = Mockito
                .mock(Transaction.ClientId.class);
        Mockito.when(clientId.toString()).thenReturn("InvalidClientID");
        Mockito.when(clientId.getEffectiveClient()).thenReturn(clientId);
        assertThrows(InvalidRequestException.class, () -> transactionsServiceV1.convertClientId(clientId));
    }

    @Test
    void shouldExecuteTransactionUserCancelOk() {
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        final Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );
        TransactionUserCanceledEvent userCanceledEvent = new TransactionUserCanceledEvent(
                transactionId
        );
        TransactionUserCancelCommand transactionCancelCommand = new TransactionUserCancelCommand(
                null,
                new TransactionId(transactionId)
        );
        when(repository.findById(transactionId)).thenReturn(Mono.just(transaction));
        when(transactionCancelHandlerV2.handle(transactionCancelCommand)).thenReturn(Mono.just(userCanceledEvent));
        when(cancellationRequestProjectionHandlerV2.handle(any())).thenReturn(Mono.empty());
        StepVerifier
                .create(transactionsServiceV1.cancelTransaction(transactionId, UUID.fromString(USER_ID)))
                .expectNext().verifyComplete();

    }

    @Test
    void shouldExecuteTransactionUserCancelKONotFound() {
        String transactionId = UUID.randomUUID().toString();
        when(repository.findById(transactionId)).thenReturn(Mono.empty());
        StepVerifier.create(transactionsServiceV1.cancelTransaction(transactionId, null))
                .expectError(TransactionNotFoundException.class).verify();

    }

    @Test
    void shouldUpdateTransactionAuthOutcomeBeIdempotentForAlreadyAuthorizedTransactionClosedRequested() {
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);

        UUID transactionIdDecoded = transactionId.uuid();

        Transaction transactionDocument = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                ZonedDateTime.now()
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeNpgGatewayDto()
                                .operationResult(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)
                )
                .timestampOperation(OffsetDateTime.now());

        TransactionInfoDto expectedResponse = new TransactionInfoDto()
                .transactionId(transactionDocument.getTransactionId())
                .payments(
                        transactionDocument.getPaymentNotices().stream().map(
                                paymentNotice -> new PaymentInfoDto()
                                        .amount(paymentNotice.getAmount())
                                        .reason(paymentNotice.getDescription())
                                        .paymentToken(paymentNotice.getPaymentToken())
                                        .rptId(paymentNotice.getRptId())
                        ).toList()
                )
                .status(TransactionStatusDto.CLOSURE_REQUESTED);

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent transactionAuthorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();
        TransactionAuthorizationCompletedEvent transactionAuthorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        new NpgTransactionGatewayAuthorizationData(
                                OperationResultDto.EXECUTED,
                                "operationId",
                                "paymentEnd2EndId",
                                null,
                                null
                        )

                );
        TransactionClosureRequestedEvent transactionClosedEvent = TransactionTestUtils
                .transactionClosureRequestedEvent();

        BaseTransaction baseTransaction = TransactionTestUtils.reduceEvents(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionAuthorizationCompletedEvent,
                transactionClosedEvent
        );
        /* preconditions */
        Mockito.when(
                transactionsEventStoreRepository.findByTransactionIdAndEventCode(
                        transactionId.value(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                )
        )
                .thenReturn(Mono.just(transactionAuthorizationCompletedEvent));
        Mockito.when(transactionsUtils.reduceEvents(any(), any(), any(), any()))
                .thenReturn(Mono.just(new it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction())).thenReturn(
                        Mono.just(
                                baseTransaction
                        )
                );
        Mockito.when(transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        Flux.fromIterable(
                                List.of(
                                        TransactionTestUtils.transactionActivateEvent(),
                                        TransactionTestUtils.transactionAuthorizationRequestedEvent()
                                )
                        )
                );
        when(transactionsUtils.convertEnumerationV1(any()))
                .thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsServiceV1
                .updateTransactionAuthorization(transactionIdDecoded, updateAuthorizationRequest).block();

        assertEquals(expectedResponse, transactionInfoResponse);
        verify(transactionUpdateAuthorizationHandlerV2, times(0)).handle(any());
        verify(authorizationUpdateProjectionHandlerV2, times(0)).handle(any());
        verify(transactionSendClosureRequestHandler, times(0)).handle(any());
        verify(closureRequestedProjectionHandler, times(0)).handle(any());

    }

    @Test
    void shouldUpdateTransactionAuthOutcomeBeIdempotentForAlreadyAuthorizedTransactionAuthorizationCompleted() {
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);

        UUID transactionIdDecoded = transactionId.uuid();

        Transaction transactionDocument = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                ZonedDateTime.now()
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeNpgGatewayDto()
                                .operationResult(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)
                )
                .timestampOperation(OffsetDateTime.now());

        TransactionInfoDto expectedResponse = new TransactionInfoDto()
                .transactionId(transactionDocument.getTransactionId())
                .payments(
                        transactionDocument.getPaymentNotices().stream().map(
                                paymentNotice -> new PaymentInfoDto()
                                        .amount(paymentNotice.getAmount())
                                        .reason(paymentNotice.getDescription())
                                        .paymentToken(paymentNotice.getPaymentToken())
                                        .rptId(paymentNotice.getRptId())
                        ).toList()
                )
                .status(TransactionStatusDto.AUTHORIZATION_COMPLETED);

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent transactionAuthorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();
        TransactionAuthorizationCompletedEvent transactionAuthorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        new NpgTransactionGatewayAuthorizationData(
                                OperationResultDto.EXECUTED,
                                "operationId",
                                "paymentEnd2EndId",
                                null,
                                null
                        )
                );
        BaseTransaction baseTransaction = TransactionTestUtils.reduceEvents(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionAuthorizationCompletedEvent
        );
        /* preconditions */
        Mockito.when(
                transactionsEventStoreRepository.findByTransactionIdAndEventCode(
                        transactionId.value(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                )
        )
                .thenReturn(Mono.just(transactionAuthorizationCompletedEvent));
        Mockito.when(transactionsUtils.reduceEvents(any(), any(), any(), any()))
                .thenReturn(Mono.just(new it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction())).thenReturn(
                        Mono.just(
                                baseTransaction
                        )
                );
        Mockito.when(transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        Flux.fromIterable(
                                List.of(
                                        TransactionTestUtils.transactionActivateEvent(),
                                        TransactionTestUtils.transactionAuthorizationRequestedEvent()
                                )
                        )
                );
        when(transactionsUtils.convertEnumerationV1(any()))
                .thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsServiceV1
                .updateTransactionAuthorization(transactionIdDecoded, updateAuthorizationRequest).block();

        assertEquals(expectedResponse, transactionInfoResponse);
        verify(transactionUpdateAuthorizationHandlerV2, times(0)).handle(any());
        verify(authorizationUpdateProjectionHandlerV2, times(0)).handle(any());
        verify(transactionSendClosureRequestHandler, times(0)).handle(any());
        verify(closureRequestedProjectionHandler, times(0)).handle(any());

    }

    @ParameterizedTest()
    @EnumSource(it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.class)
    void shouldCalculateFeesUsingEffectiveClient(
                                                 it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId clientId
    ) {
        final var authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200)
                .pspId("PSP_CODE")
                .isAllCCP(false)
                .details(
                        new CardsAuthRequestDetailsDto().orderId("orderId").detailType("cards")
                );

        final var transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );
        transaction.setClientId(clientId);

        /* preconditions */
        final var calculateFeeResponseDto = new CalculateFeeResponseDto()
                .belowThreshold(true)
                .paymentMethodName("PaymentMethodName")
                .paymentMethodDescription("PaymentMethodDescription")
                .paymentMethodStatus(it.pagopa.generated.ecommerce.paymentmethods.v2.dto.PaymentMethodStatusDto.ENABLED)
                .bundles(
                        List.of(
                                new BundleDto()
                                        .idPsp("PSP_CODE")
                                        .taxPayerFee(200l)
                        )
                );

        final var paymentMethod = new PaymentMethodResponseDto()
                .name("paymentMethodName")
                .description("desc")
                .status(PaymentMethodStatusDto.ENABLED)
                .id("id")
                .paymentTypeCode("PO")
                .addRangesItem(new RangeDto().min(0L).max(100L));

        StateResponseDto gatewayResponse = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(
                        new FieldsDto().sessionId("authorizationSessionId")
                                .addFieldsItem(new FieldDto().src("http://localhost"))
                );

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationRequestId("orderId")
                .authorizationUrl(
                        "http://localhost"
                );

        final var calculateFeeRequest = ArgumentCaptor.forClass(CalculateFeeRequestDto.class);
        Mockito.when(ecommercePaymentMethodsClient.calculateFee(any(), any(), any(), any()))
                .thenReturn(
                        Mono.just(calculateFeeResponseDto)
                );

        Mockito.when(
                ecommercePaymentMethodsClient.retrieveCardData(authorizationRequest.getPaymentInstrumentId(), "orderId")
        ).thenReturn(
                Mono.just(
                        new SessionPaymentMethodResponseDto().bin("bin").brand("VISA").sessionId("sessionId")
                                .expiringDate("0226").lastFourDigits("1234")
                )
        );

        Mockito.when(ecommercePaymentMethodsClient.getPaymentMethod(any(), any())).thenReturn(Mono.just(paymentMethod));

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(any(), any()))
                .thenReturn(Mono.just(gatewayResponse));

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        Mockito.when(
                transactionRequestAuthorizationHandlerV2
                        .handleWithCreationDate(any(TransactionRequestAuthorizationCommand.class))
        )
                .thenReturn(
                        Mono.just(
                                Tuples.of(
                                        requestAuthorizationResponse,
                                        TransactionTestUtils.transactionAuthorizationRequestedEvent()
                                )
                        )
                );

        Mockito.when(transactionsUtils.getPaymentNotices(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getTransactionTotalAmount(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getRptId(any(), anyInt())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getClientId(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getEffectiveClientId(any())).thenCallRealMethod();

        /* test */
        transactionsServiceV1
                .requestTransactionAuthorization(
                        TRANSACTION_ID,
                        UUID.fromString(USER_ID),
                        null,
                        null,
                        authorizationRequest
                ).block();

        verify(ecommercePaymentMethodsClient).calculateFee(any(), any(), calculateFeeRequest.capture(), any());
        assertEquals(clientId.getEffectiveClient().name(), calculateFeeRequest.getValue().getTouchpoint());
    }
}
