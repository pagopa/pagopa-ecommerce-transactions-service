package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.nodo.v1.dto.InformazioniPagamentoDto;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.transactions.server.model.ActivationResultRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.client.NodoPerPM;
import it.pagopa.transactions.commands.TransactionActivateResultCommand;
import it.pagopa.transactions.commands.TransactionInitializeCommand;
import it.pagopa.transactions.commands.data.ActivationResultData;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.documents.TransactionInitEvent;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.PaymentRequestInfo;
import it.pagopa.transactions.repositories.PaymentRequestsInfoRepository;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionActivateResultHandlerTest {

    @Mock private PaymentRequestsInfoRepository paymentRequestInfoRepository;

    @Mock private NodoPerPM nodoPerPM;

    @InjectMocks
    private TransactionActivateResultHandler handler;

    @Mock
    private TransactionsEventStoreRepository<TransactionInitData> transactionEventStoreRepository;

    @Test
    void shouldHandleCommandForTransactionActivateResultCallingChiediInfoNodo() {

        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String transactionId = UUID.randomUUID().toString();
        String paymentToken = UUID.randomUUID().toString();
        String idCarrello = UUID.randomUUID().toString();
        String paName = "paName";
        String paTaxcode = "77777777777";
        String description = "Description";
        Integer amount = Integer.valueOf(1000);

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        requestDto.setRptId(rptId.value());
        requestDto.setEmail("jhon.doe@email.com");
        requestDto.setAmount(1200);

        ActivationResultRequestDto activationResultRequestDto =
                new ActivationResultRequestDto()
                        .paymentToken(paymentToken);

        Transaction transaction = new Transaction(
                new TransactionId(UUID.fromString(transactionId)),
                new PaymentToken(paymentToken),
                rptId,
                new TransactionDescription("testTransactionDescription"),
                new TransactionAmount(amount),
                TransactionStatusDto.INIT_REQUESTED
                );

        ActivationResultData activationResultData = new ActivationResultData(transaction, activationResultRequestDto);

        //when(activationResultData.activationResultData()).thenReturn(activationResultRequestDto);
        //when(activationResultData.transaction()).thenReturn(transaction);

        TransactionActivateResultCommand command = new TransactionActivateResultCommand(rptId, activationResultData);

        TransactionInitEvent transactionInitEvent = new TransactionInitEvent(
                command.getData().transaction().getTransactionId().toString(),
                rptId.value(),
                paymentToken,
                new TransactionInitData(transaction.getDescription().value(), transaction.getAmount().value(), null, null, null)
        );

        PaymentRequestInfo paymentRequestInfoCachedNoToken =
                new PaymentRequestInfo(
                        rptId,
                        paTaxcode,
                        paName,
                        description,
                        amount,
                        null,
                        true,
                        null,
                        idempotencyKey);

        PaymentRequestInfo paymentRequestInfoCachedWithToken =
                new PaymentRequestInfo(
                        rptId,
                        paTaxcode,
                        paName,
                        description,
                        amount,
                        null,
                        true,
                        paymentToken,
                        idempotencyKey);

        InformazioniPagamentoDto informazioniPagamentoDto = new InformazioniPagamentoDto()
                .idCarrello(idCarrello)
                        .oggettoPagamento("OggettoPagamento")
                                .email("email@email.test")
                                        .bolloDigitale(false)
                                                .codiceFiscale(paTaxcode)
                                                        .importoTotale(new BigDecimal(amount));

        Mockito.when(paymentRequestInfoRepository.findById(rptId))
                .thenReturn(Optional.of(paymentRequestInfoCachedNoToken));
        Mockito.when(paymentRequestInfoRepository.save(Mockito.any(PaymentRequestInfo.class)))
                .thenReturn(paymentRequestInfoCachedWithToken);
        Mockito.when(nodoPerPM.chiediInformazioniPagamento(Mockito.any(String.class)))
                .thenReturn(Mono.just(informazioniPagamentoDto));
        Mockito.when(transactionEventStoreRepository.save(Mockito.any(TransactionInitEvent.class))).thenReturn(Mono.just(transactionInitEvent));

        handler.handle(command).block();

        Mockito.verify(paymentRequestInfoRepository, Mockito.times(1)).findById(rptId);
        Mockito.verify(paymentRequestInfoRepository, Mockito.times(1)).save(paymentRequestInfoCachedWithToken);
        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(Mockito.any(TransactionInitEvent.class));

    }

    @Test
    void shouldThrowsAlreadyProcessedException() {

        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String transactionId = UUID.randomUUID().toString();
        String paymentToken = UUID.randomUUID().toString();
        String idCarrello = UUID.randomUUID().toString();
        String paName = "paName";
        String paTaxcode = "77777777777";
        String description = "Description";
        Integer amount = Integer.valueOf(1000);

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        requestDto.setRptId(rptId.value());
        requestDto.setEmail("jhon.doe@email.com");
        requestDto.setAmount(1200);

        ActivationResultRequestDto activationResultRequestDto =
                new ActivationResultRequestDto()
                        .paymentToken(paymentToken);

        Transaction transaction = new Transaction(
                new TransactionId(UUID.fromString(transactionId)),
                new PaymentToken(paymentToken),
                rptId,
                new TransactionDescription("testTransactionDescription"),
                new TransactionAmount(amount),
                TransactionStatusDto.AUTHORIZED
        );


        ActivationResultData activationResultData = new ActivationResultData(transaction, activationResultRequestDto);

        TransactionActivateResultCommand command = new TransactionActivateResultCommand(rptId, activationResultData);

        /* test */
        StepVerifier.create(handler.handle(command))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(paymentRequestInfoRepository, Mockito.times(0)).findById(Mockito.any(RptId.class));
        Mockito.verify(paymentRequestInfoRepository, Mockito.times(0)).save(Mockito.any(PaymentRequestInfo.class));
        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(Mockito.any(TransactionInitEvent.class));

    }

    @Test
    void shouldThrowsTransactionNotFoundException() {

        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String transactionId = UUID.randomUUID().toString();
        String paymentToken = UUID.randomUUID().toString();
        String idCarrello = UUID.randomUUID().toString();
        String paName = "paName";
        String paTaxcode = "77777777777";
        String description = "Description";
        Integer amount = Integer.valueOf(1000);

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        requestDto.setRptId(rptId.value());
        requestDto.setEmail("jhon.doe@email.com");
        requestDto.setAmount(1200);

        ActivationResultRequestDto activationResultRequestDto =
                new ActivationResultRequestDto()
                        .paymentToken(paymentToken);

        Transaction transaction = new Transaction(
                new TransactionId(UUID.fromString(transactionId)),
                new PaymentToken(paymentToken),
                rptId,
                new TransactionDescription("testTransactionDescription"),
                new TransactionAmount(amount),
                TransactionStatusDto.INIT_REQUESTED
        );

        ActivationResultData activationResultData = new ActivationResultData(transaction, activationResultRequestDto);

        TransactionActivateResultCommand command = new TransactionActivateResultCommand(rptId, activationResultData);

        PaymentRequestInfo paymentRequestInfoCachedWithToken =
                new PaymentRequestInfo(
                        rptId,
                        paTaxcode,
                        paName,
                        description,
                        amount,
                        null,
                        true,
                        paymentToken,
                        idempotencyKey);

        InformazioniPagamentoDto informazioniPagamentoDto = new InformazioniPagamentoDto()
                .idCarrello(idCarrello)
                .oggettoPagamento("OggettoPagamento")
                .email("email@email.test")
                .bolloDigitale(false)
                .codiceFiscale(paTaxcode)
                .importoTotale(new BigDecimal(amount));

        Mockito.when(paymentRequestInfoRepository.findById(rptId))
                .thenReturn(Optional.empty());

        Mockito.when(nodoPerPM.chiediInformazioniPagamento(Mockito.any(String.class)))
                .thenReturn(Mono.just(informazioniPagamentoDto));
        //Mockito.when(transactionEventStoreRepository.save(Mockito.any())).thenReturn(Mono.just(transactionInitEvent));

        /* test */
        StepVerifier.create(handler.handle(command))
                .expectErrorMatches(error -> error instanceof TransactionNotFoundException)
                .verify();

        //handler.handle(command).block();

        Mockito.verify(paymentRequestInfoRepository, Mockito.times(1)).findById(rptId);
        Mockito.verify(paymentRequestInfoRepository, Mockito.never()).save(paymentRequestInfoCachedWithToken);
        Mockito.verify(transactionEventStoreRepository, Mockito.never()).save(Mockito.any(TransactionInitEvent.class));
    }


}
