package it.pagopa.transactions.services.v1;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.WorkflowStateDto;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.redis.templatewrappers.ExclusiveLockDocumentWrapper;
import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.redis.templatewrappers.UniqueIdTemplateWrapper;
import it.pagopa.ecommerce.commons.utils.JwtTokenUtils;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.*;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.BundleDto;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.CalculateFeeResponseDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlResponseDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.generated.wallet.v1.dto.WalletAuthCardDataDto;
import it.pagopa.generated.wallet.v1.dto.WalletAuthDataDto;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.client.WalletClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.TransactionUserCancelCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.handlers.v1.TransactionActivateHandler;
import it.pagopa.transactions.commands.handlers.v1.TransactionSendClosureHandler;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.data.redis.AutoConfigureDataRedis;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@WebFluxTest
@TestPropertySource(locations = "classpath:application-tests.properties")
@Import(
    {
            it.pagopa.transactions.services.v1.TransactionsService.class,
            it.pagopa.transactions.services.v2.TransactionsService.class,
            it.pagopa.transactions.services.v2_1.TransactionsService.class,
            it.pagopa.transactions.commands.handlers.v1.TransactionActivateHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler.class,
            it.pagopa.transactions.commands.handlers.v1.TransactionRequestAuthorizationHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler.class,
            it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v1.TransactionUpdateAuthorizationHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler.class,
            it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v1.TransactionSendClosureHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureRequestHandler.class,
            it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v1.TransactionUserCancelHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler.class,
            it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v1.TransactionRequestUserReceiptHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler.class,
            it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler.class,
            it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler.class,
            TransactionsEventStoreRepository.class,
            UUIDUtils.class
    }
)
@AutoConfigureDataRedis
class TransactionServiceTests {
    @MockBean
    private TransactionsViewRepository repository;

    @Autowired
    @Qualifier(it.pagopa.transactions.services.v1.TransactionsService.QUALIFIER_NAME)
    private it.pagopa.transactions.services.v1.TransactionsService transactionsServiceV1;

    @Autowired
    private UUIDUtils uuidUtils;

    @MockBean
    private EcommercePaymentMethodsClient ecommercePaymentMethodsClient;

    @MockBean
    private WalletClient walletClient;

    @MockBean
    private PaymentGatewayClient paymentGatewayClient;

    @MockBean
    private NodeForPspClient nodeForPspClient;

    @MockBean
    @Qualifier("transactionClosureRetryQueueAsyncClientV1")
    private QueueAsyncClient queueAsyncClientClosureRetryV1;

    @MockBean
    @Qualifier("transactionRefundQueueAsyncClientV1")
    private QueueAsyncClient queueAsyncClientRefundV1;

    @MockBean
    @Qualifier("transactionClosureRetryQueueAsyncClientV2")
    private QueueAsyncClient queueAsyncClientClosureRetryV2;

    @MockBean
    @Qualifier("transactionClosureQueueAsyncClientV2")
    private QueueAsyncClient transactionClosureQueueAsyncClientV2;

    @MockBean
    private TransactionActivateHandler transactionActivateHandler;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler transactionActivateHandlerV2;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v1.TransactionUserCancelHandler transactionCancelHandlerV1;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler transactionCancelHandlerV2;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v1.TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandlerV1;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandlerV2;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v1.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV1;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV2;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v1.TransactionRequestUserReceiptHandler transactionUpdateStatusHandlerV1;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler transactionUpdateStatusHandlerV2;

    @MockBean
    private TransactionSendClosureHandler transactionSendClosureHandler;

    @MockBean
    private it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV2;

    @MockBean
    private it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV2;

    @MockBean
    private it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler closureRequestedProjectionHandler;

    @MockBean
    private TransactionsEventStoreRepository transactionsEventStoreRepository;

    @MockBean
    private it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV2;

    @Captor
    private ArgumentCaptor<TransactionRequestAuthorizationCommand> commandArgumentCaptor;

    @MockBean
    private JwtTokenUtils jwtTokenUtils;

    @MockBean
    private TransactionsUtils transactionsUtils;

    @MockBean
    private AuthRequestDataUtils authRequestDataUtils;

    @MockBean
    private TracingUtils tracingUtils;

    @MockBean
    private PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper;

    @MockBean
    private UniqueIdTemplateWrapper uniqueIdTemplateWrapper;

    @MockBean
    private UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils;

    @MockBean
    private OpenTelemetryUtils openTelemetryUtils;

    @MockBean
    private ConfidentialMailUtils confidentialMailUtils;

    @MockBean
    private ExclusiveLockDocumentWrapper exclusiveLockDocumentWrapper;

    final String TRANSACTION_ID = TransactionTestUtils.TRANSACTION_ID;

    private static final String NPG_URL_IFRAME = "http://iframe";

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
                transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null).block(),
                expected
        );

        StepVerifier.create(transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
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
                transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null).block(),
                expected
        );

        StepVerifier.create(transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null))
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
                .paymentMethodStatus(it.pagopa.generated.ecommerce.paymentmethods.v2.dto.PaymentMethodStatusDto.ENABLED)
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

        /* TODO: Chech this response fpr this test */
        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(
                        new FieldsDto()
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(stateResponseDto.getFieldSet().getFields().get(0).getSrc());

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

        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(any(), any()))
                .thenReturn(Mono.just(stateResponseDto));

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionsUtils.getPaymentNotices(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getTransactionTotalAmount(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getRptId(any(), anyInt())).thenCallRealMethod();

        Mockito.when(transactionRequestAuthorizationHandlerV1.handle(commandArgumentCaptor.capture()))
                .thenReturn(Mono.just(requestAuthorizationResponse));

        /* test */
        StepVerifier
                .create(
                        transactionsServiceV1
                                .requestTransactionAuthorization(
                                        TRANSACTION_ID,
                                        null,
                                        null,
                                        null,
                                        authorizationRequest
                                )
                )
                .expectNext(requestAuthorizationResponse)
                .verifyComplete();

        AuthorizationRequestData captureData = commandArgumentCaptor.getValue().getData();
        assertEquals(calculateFeeResponseDto.getPaymentMethodDescription(), captureData.paymentMethodDescription());
        assertEquals(calculateFeeResponseDto.getPaymentMethodName(), captureData.paymentMethodName());
        // verify that cache delete is called for each payment notice
        transaction.getPaymentNotices().forEach(
                paymentNotice -> verify(paymentRequestInfoRedisTemplateWrapper, times(1))
                        .deleteById(paymentNotice.getRptId())
        );
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
                () -> {
                    requestAuthorizationResponseDtoMono.block();
                }
        );
        // verify that cache delete is never called
        verify(paymentRequestInfoRedisTemplateWrapper, times(0)).deleteById(any());
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
        Mockito.when(repository.findById(transactionId.value().toString()))
                .thenReturn(Mono.just(transactionDocument));

        Mockito.when(transactionUpdateStatusHandlerV1.handle(any()))
                .thenReturn(Mono.just(event));

        Mockito.when(transactionUserReceiptProjectionHandlerV1.handle(any()))
                .thenReturn(Mono.just(transactionDocument));
        when(transactionsUtils.convertEnumerationV1(any())).thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsServiceV1
                .addUserReceipt(transactionId.value().toString(), addUserReceiptRequest).block();

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
        Mockito.when(repository.findById(transactionId.value().toString()))
                .thenReturn(Mono.just(transactionDocument));

        Mockito.when(transactionUpdateStatusHandlerV1.handle(any()))
                .thenReturn(Mono.just(event));

        Mockito.when(transactionUserReceiptProjectionHandlerV1.handle(any()))
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
                .expectErrorMatches(error -> error instanceof TransactionNotFoundException)
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
// TODO: Check if this response is right
        StateResponseDto gatewayResponse = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN)
                .url("http://example.com");

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(gatewayResponse.getUrl());

        Mockito.when(ecommercePaymentMethodsClient.calculateFee(any(), any(), any(), any())).thenReturn(
                Mono.just(calculateFeeResponseDto)
        );

        Mockito.when(ecommercePaymentMethodsClient.getPaymentMethod(any(), any())).thenReturn(Mono.just(paymentMethod));

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(any(), any()))
                .thenReturn(Mono.just(gatewayResponse));

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionsUtils.getPaymentNotices(any())).thenCallRealMethod();

        Mockito.when(transactionsUtils.getTransactionTotalAmount(any())).thenCallRealMethod();

        Mockito.when(transactionRequestAuthorizationHandlerV1.handle(any()))
                .thenReturn(Mono.just(requestAuthorizationResponse));

        /* test */
        StepVerifier
                .create(
                        transactionsServiceV1
                                .requestTransactionAuthorization(
                                        TRANSACTION_ID,
                                        null,
                                        null,
                                        null,
                                        authorizationRequest
                                )
                )
                .expectErrorMatches(exception -> exception instanceof PaymentNoticeAllCCPMismatchException)
                .verify();
        // verify that cache delete is called for each payment notice
        verify(paymentRequestInfoRedisTemplateWrapper, times(0)).deleteById(any());
    }

    static Stream<Arguments> v2ClientIdMapping() {
        return Stream.of(
                Arguments.of("IO", it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO),
                Arguments.of("CHECKOUT", it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT),
                Arguments.of(
                        "CHECKOUT_CART",
                        it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT_CART
                ),
                Arguments.of(
                        "CHECKOUT_CART",
                        it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.WISP_REDIRECT
                )
        );
    }

    @ParameterizedTest
    @EnumSource(Transaction.ClientId.class)
    void shouldConvertClientIdSuccessfully(Transaction.ClientId clientId) {
        assertEquals(clientId.name(), transactionsServiceV1.convertClientId(clientId).toString());
    }

    @ParameterizedTest
    @MethodSource("v2ClientIdMapping")
    void shouldConvertV2ClientIdSuccessfully(
                                             String expected,
                                             it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId clientId
    ) {
        assertEquals(expected, transactionsServiceV1.convertClientId(clientId).toString());
    }

    @Test
    void shouldRejectNullClientId() {
        assertThrows(
                InvalidRequestException.class,
                () -> transactionsServiceV1
                        .convertClientId((it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId) null)
        );
    }

    @Test
    void shouldThrowsInvalidRequestExceptionForInvalidClientID() {
        it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId clientId = Mockito
                .mock(it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.class);
        Mockito.when(clientId.toString()).thenReturn("InvalidClientID");
        assertThrows(InvalidRequestException.class, () -> transactionsServiceV1.convertClientId(clientId));
    }

    @Test
    void shouldThrowPaymentMethodNotFoundExceptionForPaymentMethodNotFound() {
        Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(0)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT)
                .fee(0)
                .pspId("PSP_CODE")
                .isAllCCP(false)
                .details(new ApmAuthRequestDetailsDto().detailType("apm"));

        /* preconditions */

        PaymentMethodNotFoundException exception = new PaymentMethodNotFoundException(
                UUID.randomUUID().toString(),
                "CHECKOUT"
        );

        Mockito.when(ecommercePaymentMethodsClient.getPaymentMethod(any(), any())).thenReturn(Mono.error(exception));

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        /* test */

        StepVerifier.create(
                transactionsServiceV1
                        .requestTransactionAuthorization(TRANSACTION_ID, null, null, null, authorizationRequest)
        )
                .expectError(PaymentMethodNotFoundException.class)
                .verify();
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
        when(transactionCancelHandlerV1.handle(transactionCancelCommand)).thenReturn(Mono.just(userCanceledEvent));
        when(cancellationRequestProjectionHandlerV1.handle(any())).thenReturn(Mono.empty());
        StepVerifier.create(transactionsServiceV1.cancelTransaction(transactionId, null)).expectNext()
                .verifyComplete();

    }

    @Test
    void shouldExecuteTransactionUserCancelKONotFound() {
        String transactionId = UUID.randomUUID().toString();
        when(repository.findById(transactionId)).thenReturn(Mono.empty());
        StepVerifier.create(transactionsServiceV1.cancelTransaction(transactionId, null))
                .expectError(TransactionNotFoundException.class).verify();

    }

    @Test
    void shouldRedirectToAuthorizationURIForValidRequestWithNPGWalletDetail() {
        String walletId = UUID.randomUUID().toString();
        String contractId = "contractId";
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200)
                .pspId("PSP_CODE")
                .isAllCCP(false)
                .details(
                        new WalletAuthRequestDetailsDto().walletId(walletId)
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
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME);

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
                walletClient.getWalletInfo(walletId)
        ).thenReturn(
                Mono.just(
                        new WalletAuthDataDto().walletId(UUID.fromString(walletId)).brand("VISA")
                                .contractId(contractId)
                                .paymentMethodData(new WalletAuthCardDataDto().bin("bin"))
                )
        );

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(any(), any()))
                .thenReturn(Mono.just(stateResponseDto));

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionsUtils.getPaymentNotices(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getTransactionTotalAmount(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getRptId(any(), anyInt())).thenCallRealMethod();

        Mockito.when(transactionRequestAuthorizationHandlerV1.handle(commandArgumentCaptor.capture()))
                .thenReturn(Mono.just(requestAuthorizationResponse));

        /* test */
        StepVerifier
                .create(
                        transactionsServiceV1
                                .requestTransactionAuthorization(
                                        TRANSACTION_ID,
                                        null,
                                        null,
                                        null,
                                        authorizationRequest
                                )
                )
                .expectNext(requestAuthorizationResponse)
                .verifyComplete();

        AuthorizationRequestData captureData = commandArgumentCaptor.getValue().getData();
        assertEquals(Optional.empty(), captureData.sessionId());
        assertEquals(contractId, captureData.contractId().get());
        assertEquals(calculateFeeResponseDto.getPaymentMethodDescription(), captureData.paymentMethodDescription());
        assertEquals(calculateFeeResponseDto.getPaymentMethodName(), captureData.paymentMethodName());
        // verify that cache delete is called for each payment notice
        transaction.getPaymentNotices().forEach(
                paymentNotice -> verify(paymentRequestInfoRedisTemplateWrapper, times(1))
                        .deleteById(paymentNotice.getRptId())
        );
    }

    @Test
    void shouldRedirectToAuthorizationURIForValidRequestWithNPGApmDetail() {
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200)
                .pspId("PSP_CODE")
                .isAllCCP(false)
                .details(
                        new ApmAuthRequestDetailsDto()
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
                                        .taxPayerFee(200L)
                        )
                );

        PaymentMethodResponseDto paymentMethod = new PaymentMethodResponseDto()
                .name("paymentMethodName")
                .description("desc")
                .status(PaymentMethodStatusDto.ENABLED)
                .id("paymentInstrumentId")
                .paymentTypeCode("PO")
                .methodManagement(PaymentMethodManagementTypeDto.NOT_ONBOARDABLE)
                .addRangesItem(new RangeDto().min(0L).max(100L));

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl("http://example.com");

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

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionsUtils.getPaymentNotices(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getTransactionTotalAmount(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getRptId(any(), anyInt())).thenCallRealMethod();

        Mockito.when(transactionRequestAuthorizationHandlerV1.handle(commandArgumentCaptor.capture()))
                .thenReturn(Mono.just(requestAuthorizationResponse));

        /* test */
        StepVerifier
                .create(
                        transactionsServiceV1
                                .requestTransactionAuthorization(
                                        TRANSACTION_ID,
                                        null,
                                        null,
                                        null,
                                        authorizationRequest
                                )
                )
                .expectNext(requestAuthorizationResponse)
                .verifyComplete();

        AuthorizationRequestData captureData = commandArgumentCaptor.getValue().getData();
        assertEquals(Optional.empty(), captureData.sessionId());
        assertEquals(Optional.empty(), captureData.contractId());
        assertEquals(paymentMethod.getName(), captureData.brand());
        assertEquals(calculateFeeResponseDto.getPaymentMethodDescription(), captureData.paymentMethodDescription());
        assertEquals(calculateFeeResponseDto.getPaymentMethodName(), captureData.paymentMethodName());
        // verify that cache delete is called for each payment notice
        transaction.getPaymentNotices().forEach(
                paymentNotice -> verify(paymentRequestInfoRedisTemplateWrapper, times(1))
                        .deleteById(paymentNotice.getRptId())
        );
    }

    @Test
    void shouldRedirectToAuthorizationURIForValidRequestWithRedirectDetail() {
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200)
                .pspId("PSP_CODE")
                .isAllCCP(false)
                .details(
                        new RedirectionAuthRequestDetailsDto()
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
                                        .taxPayerFee(200L)
                        )
                );

        PaymentMethodResponseDto paymentMethod = new PaymentMethodResponseDto()
                .name("paymentMethodName")
                .description("desc")
                .status(PaymentMethodStatusDto.ENABLED)
                .id("paymentInstrumentId")
                .paymentTypeCode("PO")
                .addRangesItem(new RangeDto().min(0L).max(100L));

        RedirectUrlResponseDto redirectUrlResponseDto = new RedirectUrlResponseDto()
                .url("http://redirectionUrl")
                .idTransaction("idTransaction")
                .idPSPTransaction("idPspTransaction")
                .timeout(60000)
                .amount(300);

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(redirectUrlResponseDto.getUrl());

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

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(paymentGatewayClient.requestRedirectUrlAuthorization(any(), any(), any()))
                .thenReturn(Mono.just(redirectUrlResponseDto));

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionsUtils.getPaymentNotices(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getTransactionTotalAmount(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getRptId(any(), anyInt())).thenCallRealMethod();

        Mockito.when(transactionRequestAuthorizationHandlerV1.handle(commandArgumentCaptor.capture()))
                .thenReturn(Mono.just(requestAuthorizationResponse));

        /* test */
        StepVerifier
                .create(
                        transactionsServiceV1
                                .requestTransactionAuthorization(
                                        TRANSACTION_ID,
                                        null,
                                        null,
                                        null,
                                        authorizationRequest
                                )
                )
                .expectNext(requestAuthorizationResponse)
                .verifyComplete();

        AuthorizationRequestData captureData = commandArgumentCaptor.getValue().getData();
        assertEquals(Optional.empty(), captureData.sessionId());
        assertEquals(Optional.empty(), captureData.contractId());
        assertEquals("N/A", captureData.brand());
        assertEquals(calculateFeeResponseDto.getPaymentMethodDescription(), captureData.paymentMethodDescription());
        assertEquals(calculateFeeResponseDto.getPaymentMethodName(), captureData.paymentMethodName());
        // verify that cache delete is called for each payment notice
        transaction.getPaymentNotices().forEach(
                paymentNotice -> verify(paymentRequestInfoRedisTemplateWrapper, times(1))
                        .deleteById(paymentNotice.getRptId())
        );
    }

}
