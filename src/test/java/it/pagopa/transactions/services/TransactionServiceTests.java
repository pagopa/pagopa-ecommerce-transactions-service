package it.pagopa.transactions.services;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.*;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.TransactionUserCancelCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.handlers.*;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.TransactionAmountMismatchException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.*;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.JwtTokenUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@WebFluxTest
@TestPropertySource(locations = "classpath:application-tests.properties")
@Import(
    {
            TransactionsService.class,
            TransactionRequestAuthorizationHandler.class,
            AuthorizationRequestProjectionHandler.class,
            TransactionsEventStoreRepository.class,
            TransactionsActivationProjectionHandler.class,
            CancellationRequestProjectionHandler.class,
            TransactionUserCancelHandler.class,
            UUIDUtils.class
    }
)
@AutoConfigureDataRedis
class TransactionServiceTests {
    @MockBean
    private TransactionsViewRepository repository;

    @Autowired
    private TransactionsService transactionsService;

    @Autowired
    private UUIDUtils uuidUtils;

    @MockBean
    private EcommercePaymentMethodsClient ecommercePaymentMethodsClient;

    @MockBean
    private PaymentGatewayClient paymentGatewayClient;

    @MockBean
    private TransactionActivateHandler transactionActivateHandler;

    @MockBean
    private TransactionUserCancelHandler transactionCancelHandler;

    @MockBean
    private TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandler;

    @MockBean
    private TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandler;

    @MockBean
    private TransactionSendClosureHandler transactionSendClosureHandler;

    @MockBean
    private AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandler;

    @MockBean
    private ClosureSendProjectionHandler closureSendProjectionHandler;

    @MockBean
    private TransactionRequestUserReceiptHandler transactionUpdateStatusHandler;

    @MockBean
    private TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandler;

    @MockBean
    private TransactionsEventStoreRepository transactionsEventStoreRepository;

    @MockBean
    private TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> eventStoreRepositoryAuthCompletedData;

    @MockBean
    private TransactionsActivationProjectionHandler transactionsActivationProjectionHandler;

    @MockBean
    private CancellationRequestProjectionHandler cancellationRequestProjectionHandler;

    @Captor
    private ArgumentCaptor<TransactionRequestAuthorizationCommand> commandArgumentCaptor;

    @MockBean
    private ClosureErrorProjectionHandler closureErrorProjectionHandler;

    @MockBean
    private JwtTokenUtils jwtTokenUtils;

    @MockBean
    private it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId clientId;

    @MockBean
    private TransactionsUtils transactionsUtils;

    final String TRANSACTION_ID = TransactionTestUtils.TRANSACTION_ID;

    private static final String expectedOperationTimestamp = "2023-01-01T01:02:03";

    @Test
    void getTransactionReturnsTransactionDataOriginProvided() {

        final Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        transaction.setPaymentGateway("VPOS");
        transaction.setSendPaymentResultOutcome(
                it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto.OK
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
                .paymentGateway("VPOS")
                .sendPaymentResultOutcome(TransactionInfoDto.SendPaymentResultOutcomeEnum.OK)
                .authorizationCode("00")
                .authorizationErrorCode(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        when(transactionsUtils.convertEnumeration(any())).thenCallRealMethod();
        assertEquals(
                transactionsService.getTransactionInfo(TRANSACTION_ID).block(),
                expected
        );

        StepVerifier.create(transactionsService.getTransactionInfo(TRANSACTION_ID))
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
                .paymentGateway(null)
                .sendPaymentResultOutcome(null)
                .authorizationCode(null)
                .authorizationErrorCode(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        when(transactionsUtils.convertEnumeration(any())).thenCallRealMethod();
        assertEquals(
                transactionsService.getTransactionInfo(TRANSACTION_ID).block(),
                expected
        );

        StepVerifier.create(transactionsService.getTransactionInfo(TRANSACTION_ID))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void getTransactionThrowsOnTransactionNotFound() {
        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.empty());

        assertThrows(
                TransactionNotFoundException.class,
                () -> transactionsService.getTransactionInfo(TRANSACTION_ID).block(),
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
    void shouldRedirectToAuthorizationURIForValidRequest() {
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200)
                .pspId("PSP_CODE");

        Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        /* preconditions */
        CalculateFeeResponseDto calculateFeeResponseDto = new CalculateFeeResponseDto()
                .belowThreshold(true)
                .paymentMethodName("PaymentMethodName")
                .paymentMethodStatus(PaymentMethodStatusDto.ENABLED)
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

        PostePayAuthResponseEntityDto postePayAuthResponseEntityDto = new PostePayAuthResponseEntityDto()
                .channel("channel")
                .requestId("requestId")
                .urlRedirect("http://example.com");

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(postePayAuthResponseEntityDto.getUrlRedirect());

        Mockito.when(ecommercePaymentMethodsClient.calculateFee(any(), any(), any())).thenReturn(
                Mono.just(calculateFeeResponseDto)
        );

        Mockito.when(ecommercePaymentMethodsClient.getPaymentMethod(any())).thenReturn(Mono.just(paymentMethod));

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(any()))
                .thenReturn(Mono.just(postePayAuthResponseEntityDto));
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(any())).thenReturn(Mono.empty());

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionRequestAuthorizationHandler.handle(any()))
                .thenReturn(Mono.just(requestAuthorizationResponse));

        /* test */
        RequestAuthorizationResponseDto postePayAuthorizationResponse = transactionsService
                .requestTransactionAuthorization(TRANSACTION_ID, null, authorizationRequest).block();

        assertNotNull(postePayAuthorizationResponse);
        assertFalse(postePayAuthorizationResponse.getAuthorizationUrl().isEmpty());
    }

    @Test
    void shouldReturnNotFoundForNonExistingRequest() {
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(0)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("pspId");

        /* preconditions */
        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.empty());

        /* test */
        Mono<RequestAuthorizationResponseDto> requestAuthorizationResponseDtoMono = transactionsService
                .requestTransactionAuthorization(TRANSACTION_ID, null, authorizationRequest);
        assertThrows(
                TransactionNotFoundException.class,
                () -> {
                    requestAuthorizationResponseDtoMono.block();
                }
        );
    }

    @Test
    void shouldReturnTransactionInfoForSuccessfulAuthAndClosure() {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());

        String transactionIdEncoded = uuidUtils.uuidToBase64(transactionId.uuid());

        Transaction transactionDocument = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                ZonedDateTime.now()
        );

        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionDocument.getTransactionId()),
                transactionDocument.getPaymentNotices().stream().map(
                        paymentNotice -> new it.pagopa.ecommerce.commons.domain.v1.PaymentNotice(
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
                                )
                        )
                ).toList(),
                transactionDocument.getEmail(),
                "faultCode",
                "faultCodeString",
                Transaction.ClientId.CHECKOUT,
                transactionDocument.getIdCart()
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        TransactionAuthorizationCompletedData statusUpdateData = new TransactionAuthorizationCompletedData(
                "authorizationCode",
                null,
                expectedOperationTimestamp,
                null,
                it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto
                        .fromValue(
                                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                        .getOutcome().toString()
                        )
        );

        TransactionAuthorizationCompletedEvent event = new TransactionAuthorizationCompletedEvent(
                transactionDocument.getTransactionId(),
                statusUpdateData
        );

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

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
                transactionDocument.getRrn()
        );

        /* preconditions */

        Mockito.when(transactionUpdateAuthorizationHandler.handle(any()))
                .thenReturn(Mono.just(event));

        Mockito.when(authorizationUpdateProjectionHandler.handle(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionSendClosureHandler.handle(any()))
                .thenReturn(Mono.just(Either.right(closureSentEvent)));

        Mockito.when(closureSendProjectionHandler.handle(any()))
                .thenReturn(Mono.just(closedTransactionDocument));
        Mockito.when(
                eventStoreRepositoryAuthCompletedData.findByTransactionIdAndEventCode(
                        transactionId.value().toString(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT
                )
        )
                .thenReturn(Mono.empty());
        Mockito.when(transactionsUtils.reduceEvents(transactionId)).thenReturn(
                Mono.just(
                        TransactionTestUtils.transactionWithRequestedAuthorization(
                                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                                TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString())
                        )
                )
        );
        when(transactionsUtils.convertEnumeration(any())).thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsService
                .updateTransactionAuthorization(transactionIdEncoded, updateAuthorizationRequest).block();

        assertEquals(expectedResponse, transactionInfoResponse);
    }

    @Test
    void shouldReturnNotFoundExceptionForNonExistingTransactionForTransactionUpdate() {

        String transactionIdEncoded = uuidUtils.uuidToBase64(new TransactionId(TRANSACTION_ID).uuid());

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        /* preconditions */
        Mockito.when(transactionsUtils.reduceEvents(new TransactionId(TRANSACTION_ID)))
                .thenReturn(Mono.error(new TransactionNotFoundException("")));
        Mockito.when(
                eventStoreRepositoryAuthCompletedData.findByTransactionIdAndEventCode(
                        TRANSACTION_ID,
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT
                )
        )
                .thenReturn(Mono.empty());
        /* test */
        StepVerifier
                .create(
                        transactionsService
                                .updateTransactionAuthorization(transactionIdEncoded, updateAuthorizationRequest)
                )
                .expectErrorMatches(error -> error instanceof TransactionNotFoundException)
                .verify();
    }

    @Test
    void shouldReturnTransactionInfoForSuccessfulNotifiedOk() {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());

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

        Mockito.when(transactionUpdateStatusHandler.handle(any()))
                .thenReturn(Mono.just(event));

        Mockito.when(transactionUserReceiptProjectionHandler.handle(any())).thenReturn(Mono.just(transactionDocument));
        when(transactionsUtils.convertEnumeration(any())).thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsService
                .addUserReceipt(transactionId.value().toString(), addUserReceiptRequest).block();

        assertEquals(expectedResponse, transactionInfoResponse);
    }

    @Test
    void shouldReturnTransactionInfoForSuccessfulNotifiedKo() {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());

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

        Mockito.when(transactionUpdateStatusHandler.handle(any()))
                .thenReturn(Mono.just(event));

        Mockito.when(transactionUserReceiptProjectionHandler.handle(any())).thenReturn(Mono.just(transactionDocument));
        when(transactionsUtils.convertEnumeration(any()))
                .thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsService
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
        StepVerifier.create(transactionsService.addUserReceipt(TRANSACTION_ID, addUserReceiptRequest))
                .expectErrorMatches(error -> error instanceof TransactionNotFoundException)
                .verify();
    }

    @Test
    void shouldRedirectToAuthorizationURIForValidRequestWithCardData() {
        CardAuthRequestDetailsDto cardAuthRequestDetailsDto = new CardAuthRequestDetailsDto()
                .expiryDate("203012")
                .cvv("000")
                .pan("0123456789012345")
                .holderName("Name Surname");
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200)
                .pspId("PSP_CODE")
                .details(cardAuthRequestDetailsDto);
        Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSED,
                ZonedDateTime.now()
        );

        /* preconditions */
        CalculateFeeResponseDto calculateFeeResponseDto = new CalculateFeeResponseDto()
                .belowThreshold(true)
                .paymentMethodStatus(PaymentMethodStatusDto.ENABLED)
                .paymentMethodName("paymentMethodName")
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

        PostePayAuthResponseEntityDto gatewayResponse = new PostePayAuthResponseEntityDto()
                .channel("channel")
                .requestId("requestId")
                .urlRedirect("http://example.com");

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(gatewayResponse.getUrlRedirect());

        Mockito.when(ecommercePaymentMethodsClient.calculateFee(any(), any(), any())).thenReturn(
                Mono.just(calculateFeeResponseDto)
        );

        Mockito.when(ecommercePaymentMethodsClient.getPaymentMethod(any())).thenReturn(Mono.just(paymentMethod));

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(any())).thenReturn(Mono.just(gatewayResponse));
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(any())).thenReturn(Mono.empty());

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionRequestAuthorizationHandler.handle(commandArgumentCaptor.capture()))
                .thenReturn(Mono.just(requestAuthorizationResponse));
        /* test */
        RequestAuthorizationResponseDto authorizationResponse = transactionsService
                .requestTransactionAuthorization(TRANSACTION_ID, "XPAY", authorizationRequest).block();

        assertNotNull(authorizationResponse);
        assertFalse(authorizationResponse.getAuthorizationUrl().isEmpty());
        AuthorizationRequestData authData = commandArgumentCaptor.getValue().getData();
        if (authData.authDetails()instanceof CardAuthRequestDetailsDto cardDetails) {
            assertEquals(cardAuthRequestDetailsDto.getCvv(), cardDetails.getCvv());
            assertEquals(cardAuthRequestDetailsDto.getPan(), cardDetails.getPan());
            assertEquals(cardAuthRequestDetailsDto.getExpiryDate(), cardDetails.getExpiryDate());
        } else {
            fail("AuthorizationRequestData.authDetails null or not instance of CardAuthRequestDetailsDto");
        }
    }

    @Test
    void shouldReturnBadRequestForMismatchingRequestAmount() {
        CardAuthRequestDetailsDto cardAuthRequestDetailsDto = new CardAuthRequestDetailsDto()
                .expiryDate("203012")
                .cvv("000")
                .pan("0123456789012345")
                .holderName("Name Surname");
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200)
                .pspId("PSP_CODE")
                .details(cardAuthRequestDetailsDto);
        Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        CalculateFeeResponseDto calculateFeeResponseDto = new CalculateFeeResponseDto()
                .belowThreshold(true)
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

        PostePayAuthResponseEntityDto gatewayResponse = new PostePayAuthResponseEntityDto()
                .channel("channel")
                .requestId("requestId")
                .urlRedirect("http://example.com");

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(gatewayResponse.getUrlRedirect());

        Mockito.when(ecommercePaymentMethodsClient.calculateFee(any(), any(), any())).thenReturn(
                Mono.just(calculateFeeResponseDto)
        );

        Mockito.when(ecommercePaymentMethodsClient.getPaymentMethod(any())).thenReturn(Mono.just(paymentMethod));

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(any())).thenReturn(Mono.just(gatewayResponse));
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(any())).thenReturn(Mono.empty());

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        /* test */
        StepVerifier
                .create(
                        transactionsService
                                .requestTransactionAuthorization(TRANSACTION_ID, "XPAY", authorizationRequest)
                )
                .expectErrorMatches(exception -> exception instanceof TransactionAmountMismatchException);
    }

    @Test
    void shouldConvertClientIdSuccessfully() {
        for (it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId clientId : it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId
                .values()) {
            assertEquals(clientId.toString(), transactionsService.convertClientId(clientId).toString());
        }
        assertThrows(InvalidRequestException.class, () -> transactionsService.convertClientId(null));
    }

    @Test
    void shouldThrowsInvalidRequestExceptionForInvalidClientID() {
        Mockito.when(clientId.toString()).thenReturn("InvalidClientID");
        assertThrows(InvalidRequestException.class, () -> transactionsService.convertClientId(clientId));
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
        when(transactionCancelHandler.handle(transactionCancelCommand)).thenReturn(Mono.just(userCanceledEvent));
        when(cancellationRequestProjectionHandler.handle(any())).thenReturn(Mono.empty());
        StepVerifier.create(transactionsService.cancelTransaction(transactionId)).expectNext().verifyComplete();

    }

    @Test
    void shouldExecuteTransactionUserCancelKONotFound() {
        String transactionId = UUID.randomUUID().toString();
        when(repository.findById(transactionId)).thenReturn(Mono.empty());
        StepVerifier.create(transactionsService.cancelTransaction(transactionId))
                .expectError(TransactionNotFoundException.class).verify();

    }

    @Test
    void shouldUpdateTransactionAuthOutcomeBeIdempotentForAlreadyAuthorizedTransactionClosed() {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());

        String transactionIdEncoded = uuidUtils.uuidToBase64(transactionId.uuid());

        Transaction transactionDocument = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                ZonedDateTime.now()
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
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
                .status(TransactionStatusDto.CLOSED);

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent transactionAuthorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();
        TransactionAuthorizationCompletedEvent transactionAuthorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto.OK
                );
        TransactionClosedEvent transactionClosedEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);
        BaseTransaction baseTransaction = TransactionTestUtils.reduceEvents(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionAuthorizationCompletedEvent,
                transactionClosedEvent
        );
        /* preconditions */
        Mockito.when(
                eventStoreRepositoryAuthCompletedData.findByTransactionIdAndEventCode(
                        transactionId.value().toString(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT
                )
        )
                .thenReturn(Mono.just(transactionAuthorizationCompletedEvent));
        Mockito.when(transactionsUtils.reduceEvents(transactionId)).thenReturn(
                Mono.just(
                        baseTransaction
                )
        );
        when(transactionsUtils.convertEnumeration(any()))
                .thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsService
                .updateTransactionAuthorization(transactionIdEncoded, updateAuthorizationRequest).block();

        assertEquals(expectedResponse, transactionInfoResponse);
        verify(transactionUpdateAuthorizationHandler, times(0)).handle(any());
        verify(authorizationUpdateProjectionHandler, times(0)).handle(any());
        verify(transactionSendClosureHandler, times(0)).handle(any());
        verify(closureSendProjectionHandler, times(0)).handle(any());

    }

    @Test
    void shouldUpdateTransactionAuthOutcomeBeIdempotentForAlreadyAuthorizedTransactionClosureFailed() {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());

        String transactionIdEncoded = uuidUtils.uuidToBase64(transactionId.uuid());

        Transaction transactionDocument = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                ZonedDateTime.now()
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
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
                .status(TransactionStatusDto.UNAUTHORIZED);

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent transactionAuthorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();
        TransactionAuthorizationCompletedEvent transactionAuthorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto.KO
                );
        TransactionClosureFailedEvent transactionClosureFailedEvent = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.KO);
        BaseTransaction baseTransaction = TransactionTestUtils.reduceEvents(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionAuthorizationCompletedEvent,
                transactionClosureFailedEvent
        );
        /* preconditions */
        Mockito.when(
                eventStoreRepositoryAuthCompletedData.findByTransactionIdAndEventCode(
                        transactionId.value().toString(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT
                )
        )
                .thenReturn(Mono.just(transactionAuthorizationCompletedEvent));
        Mockito.when(transactionsUtils.reduceEvents(transactionId)).thenReturn(
                Mono.just(
                        baseTransaction
                )
        );
        when(transactionsUtils.convertEnumeration(any()))
                .thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsService
                .updateTransactionAuthorization(transactionIdEncoded, updateAuthorizationRequest).block();

        assertEquals(expectedResponse, transactionInfoResponse);
        verify(transactionUpdateAuthorizationHandler, times(0)).handle(any());
        verify(authorizationUpdateProjectionHandler, times(0)).handle(any());
        verify(transactionSendClosureHandler, times(0)).handle(any());
        verify(closureSendProjectionHandler, times(0)).handle(any());

    }

    @Test
    void shouldUpdateTransactionAuthOutcomeBeIdempotentForAlreadyAuthorizedTransactionAuthorizationCompleted() {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());

        String transactionIdEncoded = uuidUtils.uuidToBase64(transactionId.uuid());

        Transaction transactionDocument = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                ZonedDateTime.now()
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
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
                        it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto.OK
                );
        BaseTransaction baseTransaction = TransactionTestUtils.reduceEvents(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionAuthorizationCompletedEvent
        );
        /* preconditions */
        Mockito.when(
                eventStoreRepositoryAuthCompletedData.findByTransactionIdAndEventCode(
                        transactionId.value().toString(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT
                )
        )
                .thenReturn(Mono.just(transactionAuthorizationCompletedEvent));
        Mockito.when(transactionsUtils.reduceEvents(transactionId)).thenReturn(
                Mono.just(
                        baseTransaction
                )
        );
        when(transactionsUtils.convertEnumeration(any()))
                .thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsService
                .updateTransactionAuthorization(transactionIdEncoded, updateAuthorizationRequest).block();

        assertEquals(expectedResponse, transactionInfoResponse);
        verify(transactionUpdateAuthorizationHandler, times(0)).handle(any());
        verify(authorizationUpdateProjectionHandler, times(0)).handle(any());
        verify(transactionSendClosureHandler, times(0)).handle(any());
        verify(closureSendProjectionHandler, times(0)).handle(any());

    }

    @Test
    void shouldReturnTransactionInfoForSuccessfulAuthAndClosureKO() {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());

        String transactionIdEncoded = uuidUtils.uuidToBase64(transactionId.uuid());

        Transaction transactionDocument = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                ZonedDateTime.now()
        );

        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(transactionDocument.getTransactionId()),
                transactionDocument.getPaymentNotices().stream().map(
                        paymentNotice -> new it.pagopa.ecommerce.commons.domain.v1.PaymentNotice(
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
                                )
                        )
                ).toList(),
                transactionDocument.getEmail(),
                "faultCode",
                "faultCodeString",
                Transaction.ClientId.CHECKOUT,
                transactionDocument.getIdCart()
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        TransactionAuthorizationCompletedData statusUpdateData = new TransactionAuthorizationCompletedData(
                "authorizationCode",
                null,
                expectedOperationTimestamp,
                null,
                it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto
                        .fromValue(
                                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway())
                                        .getOutcome().toString()
                        )
        );

        TransactionAuthorizationCompletedEvent event = new TransactionAuthorizationCompletedEvent(
                transactionDocument.getTransactionId(),
                statusUpdateData
        );

        TransactionClosureErrorEvent closureSentEvent = TransactionTestUtils
                .transactionClosureErrorEvent();

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
                .status(TransactionStatusDto.CLOSURE_ERROR);

        Transaction closedTransactionDocument = new Transaction(
                transactionDocument.getTransactionId(),
                transactionDocument.getPaymentNotices(),
                null,
                transactionDocument.getEmail(),
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                Transaction.ClientId.CHECKOUT,
                ZonedDateTime.now().toString(),
                transactionDocument.getIdCart(),
                transactionDocument.getRrn()
        );

        /* preconditions */

        Mockito.when(transactionUpdateAuthorizationHandler.handle(any()))
                .thenReturn(Mono.just(event));

        Mockito.when(authorizationUpdateProjectionHandler.handle(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionSendClosureHandler.handle(any()))
                .thenReturn(Mono.just(Either.left(closureSentEvent)));

        Mockito.when(closureErrorProjectionHandler.handle(any()))
                .thenReturn(Mono.just(closedTransactionDocument));
        Mockito.when(
                eventStoreRepositoryAuthCompletedData.findByTransactionIdAndEventCode(
                        transactionId.value().toString(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT
                )
        )
                .thenReturn(Mono.empty());
        Mockito.when(transactionsUtils.reduceEvents(transactionId)).thenReturn(
                Mono.just(
                        TransactionTestUtils.transactionWithRequestedAuthorization(
                                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                                TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString())
                        )
                )
        );
        when(transactionsUtils.convertEnumeration(any()))
                .thenCallRealMethod();
        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsService
                .updateTransactionAuthorization(transactionIdEncoded, updateAuthorizationRequest).block();

        assertEquals(expectedResponse, transactionInfoResponse);
    }

}
