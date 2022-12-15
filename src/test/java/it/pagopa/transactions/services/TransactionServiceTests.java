package it.pagopa.transactions.services;

import it.pagopa.ecommerce.commons.documents.Transaction;
import it.pagopa.ecommerce.commons.documents.*;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PSPsResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PaymentMethodResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PspDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.RangeDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentInstrumentsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionActivateResultCommand;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.handlers.*;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.*;
import it.pagopa.transactions.repositories.TransactionsActivationRequestedEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
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

import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest
@TestPropertySource(locations = "classpath:application-tests.properties")
@Import(
    {
            TransactionsService.class,
            PaymentRequestsService.class,
            TransactionRequestAuthorizationHandler.class,
            TransactionsActivationRequestedProjectionHandler.class,
            AuthorizationRequestProjectionHandler.class,
            TransactionActivateResultHandler.class,
            TransactionsEventStoreRepository.class,
            TransactionsActivationProjectionHandler.class
    }
)
@AutoConfigureDataRedis
public class TransactionServiceTests {
    @MockBean
    private TransactionsViewRepository repository;

    @Autowired
    private TransactionsService transactionsService;

    @MockBean
    private EcommercePaymentInstrumentsClient ecommercePaymentInstrumentsClient;

    @MockBean
    private PaymentGatewayClient paymentGatewayClient;

    @MockBean
    private TransactionActivateHandler transactionActivateHandler;

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
    private TransactionAddUserReceiptHandler transactionUpdateStatusHandler;

    @MockBean
    private TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandler;

    @MockBean
    private PaymentRequestsService paymentRequestsService;

    @MockBean
    private TransactionActivateResultHandler transactionActivateResultHandler;

    @MockBean
    private TransactionsEventStoreRepository transactionsEventStoreRepository;

    @MockBean
    private TransactionsActivationProjectionHandler transactionsActivationProjectionHandler;

    @MockBean
    private TransactionsActivationRequestedEventStoreRepository transactionsActivationRequestedEventStoreRepository;

    @Captor
    private ArgumentCaptor<TransactionRequestAuthorizationCommand> commandArgumentCaptor;

    final String PAYMENT_TOKEN = "aaa";
    final String TRANSACION_ID = "833d303a-f857-11ec-b939-0242ac120002";

    @Test
    void getTransactionReturnsTransactionData() {

        final Transaction transaction = new Transaction(
                TRANSACION_ID,
                PAYMENT_TOKEN,
                "77777777777111111111111111111",
                "reason",
                100,
                "foo@example.com",
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED
        );
        final TransactionInfoDto expected = new TransactionInfoDto()
                .transactionId(TRANSACION_ID)
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .amount(transaction.getAmount())
                                .reason("reason")
                                .paymentToken(PAYMENT_TOKEN)
                                .authToken(null)
                                .rptId("77777777777111111111111111111")
                )
                .status(TransactionStatusDto.ACTIVATED);

        when(repository.findById(TRANSACION_ID)).thenReturn(Mono.just(transaction));

        assertEquals(
                transactionsService.getTransactionInfo(TRANSACION_ID).block(),
                expected
        );
    }

    @Test
    void getTransactionThrowsOnTransactionNotFound() {
        when(repository.findById(TRANSACION_ID)).thenReturn(Mono.empty());

        assertThrows(
                TransactionNotFoundException.class,
                () -> transactionsService.getTransactionInfo(TRANSACION_ID).block(),
                TRANSACION_ID
        );
    }

    @Test
    void getPaymentTokenByTransactionNotFound() {

        TransactionNotFoundException exception = new TransactionNotFoundException(TRANSACION_ID);

        assertEquals(
                exception.getPaymentToken(),
                TRANSACION_ID
        );
    }

    @Test
    void shouldRedirectToAuthorizationURIForValidRequest() {
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200)
                .pspId("PSP_CODE");

        Transaction transaction = new Transaction(
                TRANSACION_ID,
                PAYMENT_TOKEN,
                "77777777777111111111111111111",
                "description",
                100,
                "foo@example.com",
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED
        );

        /* preconditions */
        List<PspDto> pspDtoList = new ArrayList<>();
        pspDtoList.add(
                new PspDto()
                        .code("PSP_CODE")
                        .fixedCost(200l)
        );
        PSPsResponseDto pspResponseDto = new PSPsResponseDto();
        pspResponseDto.psp(pspDtoList);

        PaymentMethodResponseDto paymentMethod = new PaymentMethodResponseDto()
                .name("paymentMethodName")
                .description("desc")
                .status(PaymentMethodResponseDto.StatusEnum.ENABLED)
                .id("id")
                .paymentTypeCode("PO")
                .addRangesItem(new RangeDto().min(0L).max(100L));

        PostePayAuthResponseEntityDto postePayAuthResponseEntityDto = new PostePayAuthResponseEntityDto()
                .channel("channel")
                .requestId("requestId")
                .urlRedirect("http://example.com");

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(postePayAuthResponseEntityDto.getUrlRedirect());

        Mockito.when(ecommercePaymentInstrumentsClient.getPSPs(any(), any(), any())).thenReturn(
                Mono.just(pspResponseDto)
        );

        Mockito.when(ecommercePaymentInstrumentsClient.getPaymentMethod(any())).thenReturn(Mono.just(paymentMethod));

        Mockito.when(repository.findById(TRANSACION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(paymentGatewayClient.requestGeneralAuthorization(any())).thenReturn(
                Mono.zip(Mono.just(Optional.of(postePayAuthResponseEntityDto)), Mono.just(Optional.empty()))
        );

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionRequestAuthorizationHandler.handle(any()))
                .thenReturn(Mono.just(requestAuthorizationResponse));

        /* test */
        RequestAuthorizationResponseDto postePayAuthorizationResponse = transactionsService
                .requestTransactionAuthorization(TRANSACION_ID, null, authorizationRequest).block();

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
        Mockito.when(repository.findById(TRANSACION_ID))
                .thenReturn(Mono.empty());

        /* test */
        Mono<RequestAuthorizationResponseDto> requestAuthorizationResponseDtoMono = transactionsService
                .requestTransactionAuthorization(TRANSACION_ID, null, authorizationRequest);
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

        Transaction transactionDocument = new Transaction(
                transactionId.value().toString(),
                PAYMENT_TOKEN,
                "77777777777111111111111111111",
                "description",
                100,
                "foo@example.com",
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_REQUESTED
        );

        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                new PaymentToken(transactionDocument.getPaymentToken()),
                new RptId(transactionDocument.getRptId()),
                new TransactionDescription(transactionDocument.getDescription()),
                new TransactionAmount(transactionDocument.getAmount()),
                new Email(transactionDocument.getEmail()),
                "faultCode",
                "faultCodeString",
                transactionDocument.getStatus()
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        TransactionAuthorizationStatusUpdateData statusUpdateData = new TransactionAuthorizationStatusUpdateData(
                it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto
                        .fromValue(updateAuthorizationRequest.getAuthorizationResult().toString()),
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZED,
                "authorizationCode"
        );

        TransactionAuthorizationStatusUpdatedEvent event = new TransactionAuthorizationStatusUpdatedEvent(
                transactionDocument.getTransactionId(),
                transactionDocument.getRptId(),
                transactionDocument.getPaymentToken(),
                statusUpdateData
        );

        TransactionClosureSendData closureSendData = new TransactionClosureSendData(
                ClosePaymentResponseDto.OutcomeEnum.OK,
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSED
        );

        TransactionClosureSentEvent closureSentEvent = new TransactionClosureSentEvent(
                transactionDocument.getTransactionId(),
                transactionDocument.getRptId(),
                transactionDocument.getPaymentToken(),
                closureSendData
        );

        TransactionInfoDto expectedResponse = new TransactionInfoDto()
                .transactionId(transactionDocument.getTransactionId())
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .amount(transactionDocument.getAmount())
                                .authToken(null)
                                .reason(transactionDocument.getDescription())
                                .paymentToken(transactionDocument.getPaymentToken())
                                .rptId(transactionDocument.getRptId())
                )
                .status(TransactionStatusDto.CLOSED);

        Transaction closedTransactionDocument = new Transaction(
                transactionDocument.getTransactionId(),
                transactionDocument.getPaymentToken(),
                transactionDocument.getRptId(),
                transactionDocument.getDescription(),
                transactionDocument.getAmount(),
                transactionDocument.getEmail(),
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSED
        );

        /* preconditions */
        Mockito.when(repository.findById(transactionId.value().toString()))
                .thenReturn(Mono.just(transactionDocument));

        Mockito.when(transactionUpdateAuthorizationHandler.handle(any()))
                .thenReturn(Mono.just(event));

        Mockito.when(authorizationUpdateProjectionHandler.handle(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionSendClosureHandler.handle(any()))
                .thenReturn(Mono.just(closureSentEvent));

        Mockito.when(closureSendProjectionHandler.handle(any()))
                .thenReturn(Mono.just(closedTransactionDocument));

        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsService
                .updateTransactionAuthorization(transactionId.value().toString(), updateAuthorizationRequest).block();

        assertEquals(expectedResponse, transactionInfoResponse);
    }

    @Test
    void shouldReturnNotFoundExceptionForNonExistingTransaction() {

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        /* preconditions */
        Mockito.when(repository.findById(TRANSACION_ID))
                .thenReturn(Mono.empty());

        /* test */
        StepVerifier
                .create(transactionsService.updateTransactionAuthorization(TRANSACION_ID, updateAuthorizationRequest))
                .expectErrorMatches(error -> error instanceof TransactionNotFoundException)
                .verify();
    }

    @Test
    void shouldReturnTransactionInfoForSuccessfulNotified() {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());

        Transaction transactionDocument = new Transaction(
                transactionId.value().toString(),
                PAYMENT_TOKEN,
                "77777777777111111111111111111",
                "description",
                100,
                "foo@example.com",
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSED
        );

        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                new PaymentToken(transactionDocument.getPaymentToken()),
                new RptId(transactionDocument.getRptId()),
                new TransactionDescription(transactionDocument.getDescription()),
                new TransactionAmount(transactionDocument.getAmount()),
                new Email(transactionDocument.getEmail()),
                null,
                null,
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFIED
        );

        TransactionAddReceiptData transactionAddReceiptData = new TransactionAddReceiptData(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFIED
        );

        TransactionUserReceiptAddedEvent event = new TransactionUserReceiptAddedEvent(
                transactionDocument.getTransactionId(),
                transactionDocument.getRptId(),
                transactionDocument.getPaymentToken(),
                transactionAddReceiptData
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
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .amount(transactionDocument.getAmount())
                                .authToken(null)
                                .reason(transactionDocument.getDescription())
                                .paymentToken(transactionDocument.getPaymentToken())
                                .rptId(transactionDocument.getRptId())
                )
                .status(TransactionStatusDto.NOTIFIED);

        /* preconditions */
        Mockito.when(repository.findById(transactionId.value().toString()))
                .thenReturn(Mono.just(transactionDocument));

        Mockito.when(transactionUpdateStatusHandler.handle(any()))
                .thenReturn(Mono.just(event));

        Mockito.when(transactionUserReceiptProjectionHandler.handle(any())).thenReturn(Mono.just(transaction));

        /* test */
        TransactionInfoDto transactionInfoResponse = transactionsService
                .addUserReceipt(transactionId.value().toString(), addUserReceiptRequest).block();

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
        Mockito.when(repository.findById(TRANSACION_ID))
                .thenReturn(Mono.empty());

        /* test */
        StepVerifier.create(transactionsService.addUserReceipt(TRANSACION_ID, addUserReceiptRequest))
                .expectErrorMatches(error -> error instanceof TransactionNotFoundException)
                .verify();
    }

    @Test
    void shouldThrowTransacrionNotFoundExceptionWhenNotInTransactionRepository() {

        /** preconditions */

        ActivationResultRequestDto activationResultRequestDto = new ActivationResultRequestDto()
                .paymentToken(UUID.randomUUID().toString());

        Mockito.when(
                transactionsActivationRequestedEventStoreRepository
                        .findByEventCodeAndData_PaymentContextCode(any(), any())
        )
                .thenReturn(Mono.empty());

        /** test */
        StepVerifier.create(transactionsService.activateTransaction(TRANSACION_ID, activationResultRequestDto))
                .expectErrorMatches(error -> error instanceof TransactionNotFoundException)
                .verify();

    }

    @Test
    void shouldReturnTransactionActivationOk() {
        /** preconditions */

        ActivationResultRequestDto activationResultRequestDto = new ActivationResultRequestDto()
                .paymentToken(PAYMENT_TOKEN);

        Transaction transaction = new Transaction(
                TRANSACION_ID,
                PAYMENT_TOKEN,
                "77777777777111111111111111111",
                "Description",
                100,
                "foo@example.com",
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATION_REQUESTED
        );

        RptId rtpId = new RptId("77777777777111111111111111111");

        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";

        it.pagopa.ecommerce.commons.domain.TransactionActivated transactionActivated = new it.pagopa.ecommerce.commons.domain.TransactionActivated(
                new TransactionId(UUID.fromString(TRANSACION_ID)),
                new PaymentToken(PAYMENT_TOKEN),
                rtpId,
                new TransactionDescription("Description"),
                new TransactionAmount(100),
                new Email("foo@example.com"),
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_REQUESTED
        );

        TransactionActivationRequestedEvent transactionActivationRequestedEvent = new TransactionActivationRequestedEvent(
                TRANSACION_ID,
                "77777777777111111111111111111",
                PAYMENT_TOKEN,
                new TransactionActivationRequestedData(
                        TRANSACION_ID,
                        transactionActivated.getAmount().value(),
                        transactionActivated.getEmail().value(),
                        null,
                        null,
                        null
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                TRANSACION_ID,
                "77777777777111111111111111111",
                PAYMENT_TOKEN,
                new TransactionActivatedData(
                        TRANSACION_ID,
                        transactionActivated.getAmount().value(),
                        transactionActivated.getEmail().value(),
                        transactionActivated.getTransactionActivatedData().getFaultCode(),
                        transactionActivated.getTransactionActivatedData().getFaultCodeString(),
                        PAYMENT_TOKEN
                )
        );

        Mockito.when(
                transactionsActivationRequestedEventStoreRepository
                        .findByEventCodeAndData_PaymentContextCode(any(), any())
        ).thenReturn(Mono.just(transactionActivationRequestedEvent));
        Mockito.when(transactionActivateResultHandler.handle(Mockito.any(TransactionActivateResultCommand.class)))
                .thenReturn(Mono.just(transactionActivatedEvent));
        Mockito.when(transactionsActivationProjectionHandler.handle(Mockito.any(TransactionActivatedEvent.class)))
                .thenReturn(Mono.just(transactionActivated));

        /** test */

        ActivationResultResponseDto activationResultResponseDto = transactionsService
                .activateTransaction(TRANSACION_ID, activationResultRequestDto).block();

        assertEquals(ActivationResultResponseDto.OutcomeEnum.OK, activationResultResponseDto.getOutcome());
        Mockito.verify(transactionActivateResultHandler, Mockito.times(1))
                .handle(Mockito.any(TransactionActivateResultCommand.class));
        Mockito.verify(transactionsActivationProjectionHandler, Mockito.times(1))
                .handle(Mockito.any(TransactionActivatedEvent.class));

    }

    @Test
    void shouldRedirectToAuthorizationURIForValidRequestWithCardData() {
        CardAuthRequestDetailsDto cardAuthRequestDetailsDto = new CardAuthRequestDetailsDto()
                .expiryDate(LocalDate.of(2000, Month.JANUARY, 1))
                .cvv("000")
                .pan("0123456789012345")
                .holderName("Name Surname");
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200)
                .pspId("PSP_CODE")
                .details(cardAuthRequestDetailsDto);
        Transaction transaction = new Transaction(
                TRANSACION_ID,
                PAYMENT_TOKEN,
                "77777777777111111111111111111",
                "description",
                100,
                "foo@example.com",
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED
        );

        /* preconditions */
        List<PspDto> pspDtoList = new ArrayList<>();
        pspDtoList.add(
                new PspDto()
                        .code("PSP_CODE")
                        .fixedCost(200l)
        );
        PSPsResponseDto pspResponseDto = new PSPsResponseDto();
        pspResponseDto.psp(pspDtoList);

        PaymentMethodResponseDto paymentMethod = new PaymentMethodResponseDto()
                .name("paymentMethodName")
                .description("desc")
                .status(PaymentMethodResponseDto.StatusEnum.ENABLED)
                .id("id")
                .paymentTypeCode("PO")
                .addRangesItem(new RangeDto().min(0L).max(100L));

        PostePayAuthResponseEntityDto gatewayResponse = new PostePayAuthResponseEntityDto()
                .channel("channel")
                .requestId("requestId")
                .urlRedirect("http://example.com");

        RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(gatewayResponse.getUrlRedirect());

        Mockito.when(ecommercePaymentInstrumentsClient.getPSPs(any(), any(), any())).thenReturn(
                Mono.just(pspResponseDto)
        );

        Mockito.when(ecommercePaymentInstrumentsClient.getPaymentMethod(any())).thenReturn(Mono.just(paymentMethod));

        Mockito.when(repository.findById(TRANSACION_ID))
                .thenReturn(Mono.just(transaction));

        Mockito.when(paymentGatewayClient.requestGeneralAuthorization(any())).thenReturn(
                Mono.zip(Mono.just(Optional.of(gatewayResponse)), Mono.just(Optional.empty()))
        );

        Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

        Mockito.when(transactionRequestAuthorizationHandler.handle(commandArgumentCaptor.capture()))
                .thenReturn(Mono.just(requestAuthorizationResponse));
        /* test */
        RequestAuthorizationResponseDto authorizationResponse = transactionsService
                .requestTransactionAuthorization(TRANSACION_ID, "XPAY", authorizationRequest).block();

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

}
