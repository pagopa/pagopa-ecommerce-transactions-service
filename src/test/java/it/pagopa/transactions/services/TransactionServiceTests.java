package it.pagopa.transactions.services;

import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PSPsResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PspDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionInfoDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.client.EcommercePaymentInstrumentsClient;
import it.pagopa.transactions.documents.Transaction;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@TestPropertySource(locations = "classpath:application-tests.properties")
public class TransactionServiceTests {
	@Mock
	private TransactionsViewRepository repository;

	@InjectMocks
	private TransactionsService transactionsService;

	@Mock
	private EcommercePaymentInstrumentsClient ecommercePaymentInstrumentsClient;

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
				.language(RequestAuthorizationRequestDto.LanguageEnum.IT).fee(200).pspId("PSP_CODE");

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

		Mockito.when(ecommercePaymentInstrumentsClient.getPSPs(Mockito.any(), Mockito.any())).thenReturn(
				Mono.just(pspResponseDto));

		Mockito.when(repository.findByPaymentToken(paymentToken))
				.thenReturn(Mono.just(transaction));

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
				.paymentInstrumentId("paymentInstrumentId");

		/* preconditions */
		Mockito.when(repository.findByPaymentToken(paymentToken))
				.thenReturn(Mono.empty());

		/* test */
		assertThrows(
				TransactionNotFoundException.class,
				() -> transactionsService.requestTransactionAuthorization(paymentToken, authorizationRequest).block());
	}
}
