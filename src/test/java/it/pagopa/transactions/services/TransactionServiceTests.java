package it.pagopa.transactions.services;

import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PSPsResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PspDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentInstrumentsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.handlers.TransactionClosureRequestHandler;
import it.pagopa.transactions.commands.handlers.TransactionInizializeHandler;
import it.pagopa.transactions.commands.handlers.TransactionRequestAuthorizationHandler;
import it.pagopa.transactions.commands.handlers.TransactionUpdateAuthorizationHandler;
import it.pagopa.transactions.documents.*;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.AuthorizationRequestProjectionHandler;
import it.pagopa.transactions.projections.handlers.AuthorizationUpdateProjectionHandler;
import it.pagopa.transactions.projections.handlers.ClosureRequestProjectionHandler;
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
	private TransactionClosureRequestHandler transactionClosureRequestHandler;

	@MockBean
	private AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandler;

	@MockBean
	private ClosureRequestProjectionHandler closureRequestProjectionHandler;

	@Test
	void getTransactionReturnsTransactionData() {
		final String PAYMENT_TOKEN = "aaa";
		final Transaction transaction = new Transaction(PAYMENT_TOKEN, "rptId", "reason", 100,
				TransactionStatusDto.INITIALIZED);
		final TransactionInfoDto expected = new TransactionInfoDto()
				.amount(transaction.getAmount())
				.reason("reason")
				.paymentToken(PAYMENT_TOKEN)
				.authToken(null)
				.rptId("rptId")
				.status(TransactionStatusDto.INITIALIZED);

		when(repository.findByPaymentToken(PAYMENT_TOKEN)).thenReturn(Mono.just(transaction));

		assertEquals(
				transactionsService.getTransactionInfo(PAYMENT_TOKEN).block(),
				expected);
	}

	@Test
	void getTransactionThrowsOnTransactionNotFound() {
		final String PAYMENT_TOKEN = "aaa";
		when(repository.findByPaymentToken(PAYMENT_TOKEN)).thenReturn(Mono.empty());

		assertThrows(
				TransactionNotFoundException.class,
				() -> transactionsService.getTransactionInfo(PAYMENT_TOKEN).block(), PAYMENT_TOKEN);
	}

	@Test
	void getPaymentTokenByTransactionNotFound() {
		final String PAYMENT_TOKEN = "aaa";

		TransactionNotFoundException exception = new TransactionNotFoundException(PAYMENT_TOKEN);

		assertEquals(
				exception.getPaymentToken(),
				PAYMENT_TOKEN);
	}

	@Test
	void shouldRedirectToAuthorizationURIForValidRequest() {
		String paymentToken = "paymentToken";
		RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
				.amount(100)
				.paymentInstrumentId("paymentInstrumentId")
				.language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200)
				.pspId("PSP_CODE");

		Transaction transaction = new Transaction(
				paymentToken,
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

		Mockito.when(repository.findByPaymentToken(paymentToken))
				.thenReturn(Mono.just(transaction));

		Mockito.when(paymentGatewayClient.requestAuthorization(any())).thenReturn(
				Mono.just(requestAuthorizationResponse)
		);

		Mockito.when(repository.save(any())).thenReturn(Mono.just(transaction));

		Mockito.when(transactionRequestAuthorizationHandler.handle(any())).thenReturn(Mono.just(requestAuthorizationResponse));

		/* test */
		RequestAuthorizationResponseDto authorizationResponse = transactionsService
				.requestTransactionAuthorization(paymentToken, authorizationRequest).block();

		assertNotNull(authorizationResponse);
		assertFalse(authorizationResponse.getAuthorizationUrl().isEmpty());
	}

	@Test
	void shouldReturnNotFoundForNonExistingRequest() {
		String paymentToken = "paymentToken";
		RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
				.amount(100)
				.fee(0)
				.paymentInstrumentId("paymentInstrumentId")
				.pspId("pspId");

		/* preconditions */
		Mockito.when(repository.findByPaymentToken(paymentToken))
				.thenReturn(Mono.empty());

		/* test */
		assertThrows(
				TransactionNotFoundException.class,
				() -> transactionsService.requestTransactionAuthorization(paymentToken, authorizationRequest).block());
	}

	@Test
	void shouldReturnTransactionInfoForSuccessfulAuthAndClosure() {
		String paymentToken = "paymentToken";

		Transaction transactionDocument = new Transaction(
				paymentToken,
				"rptId",
				"description",
				100,
				TransactionStatusDto.AUTHORIZATION_REQUESTED);

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
				transactionDocument.getRptId(),
				transactionDocument.getPaymentToken(),
				statusUpdateData
		);

		TransactionClosureRequestData closureRequestData = new TransactionClosureRequestData(ClosePaymentResponseDto.EsitoEnum.OK, TransactionStatusDto.CLOSED);

		TransactionClosureRequestedEvent closureRequestedEvent = new TransactionClosureRequestedEvent(
				transactionDocument.getRptId(),
				transactionDocument.getPaymentToken(),
				closureRequestData
		);

		TransactionInfoDto expectedResponse = new TransactionInfoDto()
				.amount(transactionDocument.getAmount())
				.authToken(null)
				.status(TransactionStatusDto.CLOSED)
				.reason(transactionDocument.getDescription())
				.paymentToken(paymentToken)
				.rptId(transactionDocument.getRptId());


		Transaction closedTransactionDocument = new Transaction(
				transactionDocument.getPaymentToken(),
				transactionDocument.getRptId(),
				transactionDocument.getDescription(),
				transactionDocument.getAmount(),
				TransactionStatusDto.CLOSED);

		/* preconditions */
		Mockito.when(repository.findByPaymentToken(paymentToken))
				.thenReturn(Mono.just(transactionDocument));

		Mockito.when(transactionUpdateAuthorizationHandler.handle(any()))
				.thenReturn(Mono.just(event));

		Mockito.when(authorizationUpdateProjectionHandler.handle(any())).thenReturn(Mono.just(transactionDocument));

		Mockito.when(transactionClosureRequestHandler.handle(any()))
				.thenReturn(Mono.just(closureRequestedEvent));

		Mockito.when(closureRequestProjectionHandler.handle(any()))
				.thenReturn(Mono.just(closedTransactionDocument));

		/* test */
		TransactionInfoDto transactionInfoResponse = transactionsService.updateTransactionAuthorization(paymentToken, updateAuthorizationRequest).block();

		assertEquals(expectedResponse, transactionInfoResponse);
	}

	@Test
	void shouldReturnNotFoundExceptionForNonExistingTransaction() {
		String paymentToken = "paymentToken";

		UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
				.authorizationResult(AuthorizationResultDto.OK)
				.authorizationCode("authorizationCode")
				.timestampOperation(OffsetDateTime.now());

		/* preconditions */
		Mockito.when(repository.findByPaymentToken(paymentToken))
				.thenReturn(Mono.empty());

		/* test */
		StepVerifier.create(transactionsService.updateTransactionAuthorization(paymentToken, updateAuthorizationRequest))
				.expectErrorMatches(error -> error instanceof TransactionNotFoundException)
				.verify();
	}
}
