package it.pagopa.transactions.services;

import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PSPsResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PspDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentInstrumentsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionActivateResultCommand;
import it.pagopa.transactions.commands.handlers.*;
import it.pagopa.transactions.documents.*;
import it.pagopa.transactions.documents.Transaction;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.*;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest
@TestPropertySource(locations = "classpath:application-tests.properties")
@Import({
		TransactionsService.class,
		PaymentRequestsService.class,
		TransactionRequestAuthorizationHandler.class,
		TransactionsActivationRequestedProjectionHandler.class,
		AuthorizationRequestProjectionHandler.class,
		TransactionActivateResultHandler.class,
		TransactionsEventStoreRepository.class,
		TransactionsActivationProjectionHandler.class})
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
	private TransactionUpdateStatusHandler transactionUpdateStatusHandler;

	@MockBean
	private TransactionUpdateProjectionHandler transactionUpdateProjectionHandler;

	@MockBean
	private PaymentRequestsService paymentRequestsService;

	@MockBean
	private TransactionActivateResultHandler transactionActivateResultHandler;

	@MockBean
	private TransactionsEventStoreRepository transactionsEventStoreRepository;

	@MockBean
	private TransactionsActivationProjectionHandler transactionsActivationProjectionHandler;

	final String PAYMENT_TOKEN = "aaa";
	final String TRANSACION_ID = "833d303a-f857-11ec-b939-0242ac120002";

	@Test
	void getTransactionReturnsTransactionData() {

		final Transaction transaction = new Transaction(TRANSACION_ID, PAYMENT_TOKEN, "77777777777111111111111111111", "reason", 100,
				"foo@example.com", TransactionStatusDto.ACTIVATED);
		final TransactionInfoDto expected = new TransactionInfoDto()
		        .transactionId(TRANSACION_ID)
				.amount(transaction.getAmount())
				.reason("reason")
				.paymentToken(PAYMENT_TOKEN)
				.authToken(null)
				.rptId("77777777777111111111111111111")
				.status(TransactionStatusDto.ACTIVATED);

		when(repository.findById(TRANSACION_ID)).thenReturn(Mono.just(transaction));

		assertEquals(
				transactionsService.getTransactionInfo(TRANSACION_ID).block(),
				expected);
	}

	@Test
	void getTransactionThrowsOnTransactionNotFound() {
		when(repository.findById(TRANSACION_ID)).thenReturn(Mono.empty());

		assertThrows(
				TransactionNotFoundException.class,
				() -> transactionsService.getTransactionInfo(TRANSACION_ID).block(), TRANSACION_ID);
	}

	@Test
	void getPaymentTokenByTransactionNotFound() {

		TransactionNotFoundException exception = new TransactionNotFoundException(TRANSACION_ID);

		assertEquals(
				exception.getPaymentToken(),
				TRANSACION_ID);
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
				TransactionStatusDto.ACTIVATED);

		/* preconditions */
		List<PspDto> pspDtoList = new ArrayList<>();
		pspDtoList.add(
				new PspDto()
						.code("PSP_CODE")
						.fixedCost(2.0));
		PSPsResponseDto pspResponseDto = new PSPsResponseDto();
		pspResponseDto.psp(pspDtoList);

		RequestAuthorizationResponseDto requestAuthorizationResponse = new RequestAuthorizationResponseDto()
				.authorizationUrl("https://example.com");

		Mockito.when(ecommercePaymentInstrumentsClient.getPSPs(any(), any())).thenReturn(
				Mono.just(pspResponseDto));

		Mockito.when(repository.findById(TRANSACION_ID))
				.thenReturn(Mono.just(transaction));

		Mockito.when(paymentGatewayClient.requestAuthorization(any())).thenReturn(
				Mono.just(requestAuthorizationResponse));

		Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

		Mockito.when(transactionRequestAuthorizationHandler.handle(any())).thenReturn(Mono.just(requestAuthorizationResponse));

		/* test */
		RequestAuthorizationResponseDto authorizationResponse = transactionsService
				.requestTransactionAuthorization(TRANSACION_ID, authorizationRequest).block();

		assertNotNull(authorizationResponse);
		assertFalse(authorizationResponse.getAuthorizationUrl().isEmpty());
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
		Mono<RequestAuthorizationResponseDto> requestAuthorizationResponseDtoMono = transactionsService.requestTransactionAuthorization(TRANSACION_ID, authorizationRequest);
		assertThrows(
				TransactionNotFoundException.class,
				() -> {
					requestAuthorizationResponseDtoMono.block();
				});
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
				TransactionStatusDto.AUTHORIZATION_REQUESTED);

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

		TransactionAuthorizationStatusUpdateData statusUpdateData =
				new TransactionAuthorizationStatusUpdateData(
						updateAuthorizationRequest.getAuthorizationResult(),
						TransactionStatusDto.AUTHORIZED
				);

		TransactionAuthorizationStatusUpdatedEvent event = new TransactionAuthorizationStatusUpdatedEvent(
				transactionDocument.getTransactionId(),
				transactionDocument.getRptId(),
				transactionDocument.getPaymentToken(),
				statusUpdateData
		);

		TransactionClosureSendData closureSendData = new TransactionClosureSendData(ClosePaymentResponseDto.OutcomeEnum.OK, TransactionStatusDto.CLOSED);

		TransactionClosureSentEvent closureSentEvent = new TransactionClosureSentEvent(
				transactionDocument.getTransactionId(),
				transactionDocument.getRptId(),
				transactionDocument.getPaymentToken(),
				closureSendData
		);

		TransactionInfoDto expectedResponse = new TransactionInfoDto()
				.transactionId(transactionDocument.getTransactionId())
				.amount(transactionDocument.getAmount())
				.authToken(null)
				.status(TransactionStatusDto.CLOSED)
				.reason(transactionDocument.getDescription())
				.paymentToken(transactionDocument.getPaymentToken())
				.rptId(transactionDocument.getRptId());

		Transaction closedTransactionDocument = new Transaction(
				transactionDocument.getTransactionId(),
				transactionDocument.getPaymentToken(),
				transactionDocument.getRptId(),
				transactionDocument.getDescription(),
				transactionDocument.getAmount(),
				transactionDocument.getEmail(),
				TransactionStatusDto.CLOSED);

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
		TransactionInfoDto transactionInfoResponse = transactionsService.updateTransactionAuthorization(transactionId.value().toString(), updateAuthorizationRequest).block();

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
		StepVerifier.create(transactionsService.updateTransactionAuthorization(TRANSACION_ID, updateAuthorizationRequest))
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
				TransactionStatusDto.CLOSED);

		TransactionActivated transaction = new TransactionActivated(
				new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
				new PaymentToken(transactionDocument.getPaymentToken()),
				new RptId(transactionDocument.getRptId()),
				new TransactionDescription(transactionDocument.getDescription()),
				new TransactionAmount(transactionDocument.getAmount()),
				new Email(transactionDocument.getEmail()),
                null, null, TransactionStatusDto.NOTIFIED
		);

		UpdateTransactionStatusRequestDto updateTransactionStatusRequest = new UpdateTransactionStatusRequestDto()
				.authorizationResult(AuthorizationResultDto.OK)
				.authorizationCode("authorizationCode")
				.timestampOperation(OffsetDateTime.now());

		TransactionStatusUpdateData statusUpdateData =
				new TransactionStatusUpdateData(
					updateTransactionStatusRequest.getAuthorizationResult(),
						TransactionStatusDto.NOTIFIED
				);

		TransactionStatusUpdatedEvent event = new TransactionStatusUpdatedEvent(
				transactionDocument.getTransactionId(),
				transactionDocument.getRptId(),
				transactionDocument.getPaymentToken(),
				statusUpdateData
		);

		TransactionInfoDto expectedResponse = new TransactionInfoDto()
				.transactionId(transactionDocument.getTransactionId())
				.amount(transactionDocument.getAmount())
				.authToken(null)
				.status(TransactionStatusDto.NOTIFIED)
				.reason(transactionDocument.getDescription())
				.paymentToken(transactionDocument.getPaymentToken())
				.rptId(transactionDocument.getRptId());

		/* preconditions */
		Mockito.when(repository.findById(transactionId.value().toString()))
				.thenReturn(Mono.just(transactionDocument));

		Mockito.when(transactionUpdateStatusHandler.handle(any()))
				.thenReturn(Mono.just(event));

		Mockito.when(transactionUpdateProjectionHandler.handle(any())).thenReturn(Mono.just(transaction));

		/* test */
		TransactionInfoDto transactionInfoResponse = transactionsService.updateTransactionStatus(transactionId.value().toString(), updateTransactionStatusRequest).block();

		assertEquals(expectedResponse, transactionInfoResponse);
	}

	@Test
	void shouldReturnNotFoundExceptionForNonExistingToUpdateTransaction() {

		UpdateTransactionStatusRequestDto updateTransactionStatusRequest = new UpdateTransactionStatusRequestDto()
				.authorizationResult(AuthorizationResultDto.OK)
				.authorizationCode("authorizationCode")
				.timestampOperation(OffsetDateTime.now());

		/* preconditions */
		Mockito.when(repository.findById(TRANSACION_ID))
				.thenReturn(Mono.empty());

		/* test */
		StepVerifier.create(transactionsService.updateTransactionStatus(TRANSACION_ID, updateTransactionStatusRequest))
				.expectErrorMatches(error -> error instanceof TransactionNotFoundException)
				.verify();
	}

	@Test
	void shouldThrowTransacrionNotFoundExceptionWhenNotInTransactionRepository() {

		/** preconditions */

		ActivationResultRequestDto activationResultRequestDto = new ActivationResultRequestDto().paymentToken(UUID.randomUUID().toString());

		Mockito.when(repository.findById(TRANSACION_ID))
				.thenReturn(Mono.empty());

		/** test */
		StepVerifier.create(transactionsService.activateTransaction(TRANSACION_ID, activationResultRequestDto))
				.expectErrorMatches(error -> error instanceof TransactionNotFoundException)
				.verify();

	}

	@Test
	void shouldReturnTransactionActivationOk() {
		/** preconditions */

		ActivationResultRequestDto activationResultRequestDto = new ActivationResultRequestDto().paymentToken(PAYMENT_TOKEN);

		Transaction transaction = new Transaction(
				TRANSACION_ID,
				PAYMENT_TOKEN,
				"77777777777111111111111111111",
				"Description",
				100,
				"foo@example.com",
				TransactionStatusDto.ACTIVATION_REQUESTED
		);

		RptId rtpId = new RptId("77777777777111111111111111111");

		String faultCode = "faultCode";
		String faultCodeString = "faultCodeString";

		it.pagopa.transactions.domain.TransactionActivated transactionActivated = new it.pagopa.transactions.domain.TransactionActivated(
				new TransactionId(UUID.fromString(TRANSACION_ID)),
				new PaymentToken(PAYMENT_TOKEN),
				rtpId,
				new TransactionDescription("Description"),
				new TransactionAmount(100),
				new Email("foo@example.com"),
				faultCode,
				faultCodeString,
				TransactionStatusDto.AUTHORIZATION_REQUESTED
		);

		TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
				TRANSACION_ID,
				"77777777777111111111111111111",
				PAYMENT_TOKEN,
				new TransactionActivatedData(TRANSACION_ID,
						transactionActivated.getAmount().value(),
						transactionActivated.getEmail().value(),
						transactionActivated.getTransactionActivatedData().getFaultCode(),
						transactionActivated.getTransactionActivatedData().getFaultCodeString(),
						PAYMENT_TOKEN
				)
		);

		Mockito.when(repository.findById(TRANSACION_ID)).thenReturn(Mono.just(transaction));
		Mockito.when(transactionActivateResultHandler.handle(Mockito.any(TransactionActivateResultCommand.class))).thenReturn(Mono.just(transactionActivatedEvent));
		Mockito.when(transactionsActivationProjectionHandler.handle(Mockito.any(TransactionActivatedEvent.class))).thenReturn(Mono.just(transactionActivated));

		/** test */

		ActivationResultResponseDto activationResultResponseDto = transactionsService.activateTransaction(TRANSACION_ID, activationResultRequestDto).block();

		assertEquals( ActivationResultResponseDto.OutcomeEnum.OK, activationResultResponseDto.getOutcome());
		Mockito.verify(transactionActivateResultHandler, Mockito.times(1)).handle(Mockito.any(TransactionActivateResultCommand.class));
		Mockito.verify(transactionsActivationProjectionHandler, Mockito.times(1)).handle(Mockito.any(TransactionActivatedEvent.class));

	}

}
