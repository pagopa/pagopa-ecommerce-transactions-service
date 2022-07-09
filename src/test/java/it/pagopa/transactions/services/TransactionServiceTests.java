package it.pagopa.transactions.services;

import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PSPsResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PspDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentInstrumentsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.handlers.TransactionSendClosureHandler;
import it.pagopa.transactions.commands.handlers.TransactionInizializeHandler;
import it.pagopa.transactions.commands.handlers.TransactionRequestAuthorizationHandler;
import it.pagopa.transactions.commands.handlers.TransactionUpdateAuthorizationHandler;
import it.pagopa.transactions.commands.handlers.TransactionUpdateStatusHandler;
import it.pagopa.transactions.documents.*;
import it.pagopa.transactions.documents.Transaction;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.AuthorizationRequestProjectionHandler;
import it.pagopa.transactions.projections.handlers.AuthorizationUpdateProjectionHandler;
import it.pagopa.transactions.projections.handlers.ClosureSendProjectionHandler;
import it.pagopa.transactions.projections.handlers.TransactionUpdateProjectionHandler;
import it.pagopa.transactions.projections.handlers.TransactionsProjectionHandler;
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
import static org.mockito.Mockito.when;

@WebFluxTest
@TestPropertySource(locations = "classpath:application-tests.properties")
@Import({TransactionsService.class, TransactionRequestAuthorizationHandler.class, TransactionsProjectionHandler.class, AuthorizationRequestProjectionHandler.class})
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
	private TransactionInizializeHandler transactionInizializeHandler;

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
	
	final String PAYMENT_TOKEN = "aaa";
	final String TRANSACION_ID = "833d303a-f857-11ec-b939-0242ac120002";

	@Test
	void getTransactionReturnsTransactionData() {

		final Transaction transaction = new Transaction(TRANSACION_ID, PAYMENT_TOKEN, "rptId", "reason", 100,
				TransactionStatusDto.INITIALIZED);
		final TransactionInfoDto expected = new TransactionInfoDto()
		        .transactionId(TRANSACION_ID)
				.amount(transaction.getAmount())
				.reason("reason")
				.paymentToken(PAYMENT_TOKEN)
				.authToken(null)
				.rptId("rptId")
				.status(TransactionStatusDto.INITIALIZED);

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
				"rptId",
				"description",
				100,
				TransactionStatusDto.INITIALIZED);

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
		assertThrows(
				TransactionNotFoundException.class,
				() -> transactionsService.requestTransactionAuthorization(TRANSACION_ID, authorizationRequest).block());
	}

	@Test
	void shouldReturnTransactionInfoForSuccessfulAuthAndClosure() {
	    TransactionId transactionId = new TransactionId(UUID.randomUUID());

		Transaction transactionDocument = new Transaction(
			    transactionId.value().toString(),
				PAYMENT_TOKEN,
				"rptId",
				"description",
				100,
				TransactionStatusDto.AUTHORIZATION_REQUESTED);

		it.pagopa.transactions.domain.Transaction transaction = new it.pagopa.transactions.domain.Transaction(
				new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
				new PaymentToken(transactionDocument.getPaymentToken()),
				new RptId(transactionDocument.getRptId()),
				new TransactionDescription(transactionDocument.getDescription()),
				new TransactionAmount(transactionDocument.getAmount()),
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

		TransactionClosureSendData closureSendData = new TransactionClosureSendData(ClosePaymentResponseDto.EsitoEnum.OK, TransactionStatusDto.CLOSED);

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
}
