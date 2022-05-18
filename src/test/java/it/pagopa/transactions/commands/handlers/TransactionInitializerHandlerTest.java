package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionTokenDto;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeRes;
import it.pagopa.generated.transactions.model.ObjectFactory;
import it.pagopa.generated.transactions.server.model.BeneficiaryDto;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionsCommand;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.model.IdempotencyKey;
import it.pagopa.transactions.model.RptId;
import it.pagopa.transactions.projections.TransactionsProjection;
import it.pagopa.transactions.repositories.TransactionTokens;
import it.pagopa.transactions.repositories.TransactionTokensRepository;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonatype.aether.SessionData;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith(MockitoExtension.class)
public class TransactionInitializerHandlerTest {

    @InjectMocks
    private TransactionInizializeHandler handler;

    @Mock
    private TransactionTokensRepository transactionTokensRepository;
    @Mock
    private ObjectFactory objectFactory;
    @Mock
    private NodeForPspClient nodeForPspClient;
    @Mock
    private EcommerceSessionsClient ecommerceSessionsClient;
    @Mock
    private TransactionsEventStoreRepository<TransactionInitData> transactionEventStoreRepository;

    @Test
    public void shouldHandleMessage() {
        RptId TEST_RPTID = new RptId("77777777777302016723749670035");
        IdempotencyKey TEST_KEY = new IdempotencyKey("32009090901", "aabbccddee");
        String TEST_TOKEN = UUID.randomUUID().toString();
        TransactionsCommand<NewTransactionRequestDto> command = new TransactionsCommand<>();

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        requestDto.setRptId(TEST_RPTID.getRptId());
        requestDto.setEmail("jhon.doe@email.com");

        command.setRptId(TEST_RPTID);
        command.setData(requestDto);

        ActivatePaymentNoticeRes activateRes = new ActivatePaymentNoticeRes();
        activateRes.setFiscalCodePA("32009090901");
        activateRes.setPaymentToken(TEST_TOKEN);
        activateRes.setCompanyName("Company");
        activateRes.setTotalAmount(BigDecimal.TEN);
        activateRes.setCreditorReferenceId("1");
        activateRes.setOfficeName("Name");

        TransactionTokens tokens = new TransactionTokens(TEST_RPTID, TEST_KEY, TEST_TOKEN);

        SessionTokenDto sessionTokenDto = new SessionTokenDto()
                .email(requestDto.getEmail())
                .sessionToken(TEST_TOKEN)
                .paymentToken(UUID.randomUUID().toString())
                .rptId(TEST_RPTID.getRptId());

        /**
         * preconditions
         */
        Mockito.when(transactionTokensRepository.findById(TEST_RPTID)).thenReturn(Optional.of(tokens));
        Mockito.when(objectFactory.createActivatePaymentNoticeReq()).thenReturn(new ActivatePaymentNoticeReq());
        Mockito.when(nodeForPspClient.activatePaymentNotice(Mockito.any())).thenReturn(Mono.just(activateRes));
        Mockito.when(transactionEventStoreRepository.save(Mockito.any())).thenReturn(Mono.empty());
        Mockito.when(transactionTokensRepository.save(Mockito.any(TransactionTokens.class))).thenReturn(tokens);
        Mockito.when(ecommerceSessionsClient.createSessionToken(new SessionDataDto()
                .email(requestDto.getEmail())
                .paymentToken(activateRes.getPaymentToken())
                .rptId(requestDto.getRptId()))).thenReturn(Mono.just(sessionTokenDto));

        /**
         * preconditions
         */
        NewTransactionResponseDto response = handler.handle(command).block();

        /**
         * asserts
         */
        Mockito.verify(transactionTokensRepository, Mockito.times(1)).findById(TEST_RPTID);
        Mockito.verify(ecommerceSessionsClient, Mockito.times(1)).createSessionToken(Mockito.any());

        assertEquals(sessionTokenDto.getRptId(), response.getRptId());
    }

    @Test
    public void shouldCreateANewKey() {
        RptId TEST_RPTID = new RptId("77777777777302016723749670035");
        IdempotencyKey TEST_KEY = new IdempotencyKey("32009090901", "aabbccddee");
        String TEST_TOKEN = UUID.randomUUID().toString();
        TransactionsCommand<NewTransactionRequestDto> command = new TransactionsCommand<>();

        NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
        requestDto.setRptId(TEST_RPTID.getRptId());
        requestDto.setEmail("jhon.doe@email.com");

        command.setRptId(TEST_RPTID);
        command.setData(requestDto);

        ActivatePaymentNoticeRes activateRes = new ActivatePaymentNoticeRes();
        activateRes.setFiscalCodePA("32009090901");
        activateRes.setPaymentToken(TEST_TOKEN);
        activateRes.setCompanyName("Company");
        activateRes.setTotalAmount(BigDecimal.TEN);
        activateRes.setCreditorReferenceId("1");
        activateRes.setOfficeName("Name");


        SessionTokenDto sessionTokenDto = new SessionTokenDto()
                .email(requestDto.getEmail())
                .sessionToken(TEST_TOKEN)
                .paymentToken(UUID.randomUUID().toString())
                .rptId(TEST_RPTID.getRptId());

        /**
         * preconditions
         */
        Mockito.when(transactionTokensRepository.findById(TEST_RPTID)).thenReturn(Optional.empty());

        /**
         * preconditions
         */
        handler.handle(command).block();

        /**
         * asserts
         */
        Mockito.verify(transactionTokensRepository, Mockito.times(1)).save(Mockito.any());
    }


    @Test
    public void transactionsProjectionTests(){
        String TEST_RPTID = "77777777777302016723749670035";
        String TEST_TOKEN = "token";

        TransactionsProjection<NewTransactionResponseDto> transactionsProjection = new TransactionsProjection<>();
        transactionsProjection.setData(new NewTransactionResponseDto()
                .amount(1)
                .rptId(TEST_RPTID)
                .paymentToken(TEST_TOKEN)
                .authToken(TEST_TOKEN)
                .reason("")
                .beneficiary(new BeneficiaryDto()
                        .beneficiaryId("1")
                        .address("Via X")
                        .city("Roma")
                        .codiceUnitOperBeneficiario("1")
                        .country("Italia")
                        .denominazioneBeneficiario("Denominazione"
                        )));

        TransactionsProjection<NewTransactionResponseDto> differentTransactionsProjection = new TransactionsProjection<>();
        differentTransactionsProjection.setData(new NewTransactionResponseDto()
                .amount(1)
                .rptId(TEST_RPTID)
                .paymentToken(TEST_TOKEN)
                .authToken(TEST_TOKEN)
                .reason("")
                .beneficiary(new BeneficiaryDto()
                        .beneficiaryId("2")
                        .address("Via Y")
                        .city("Roma")
                        .codiceUnitOperBeneficiario("1")
                        .country("Italia")
                        .denominazioneBeneficiario("Denominazione"
                        )));

        differentTransactionsProjection.setRptId(new RptId(TEST_RPTID));

        assertFalse(transactionsProjection.equals(differentTransactionsProjection));
        assertFalse(transactionsProjection.getData().equals(differentTransactionsProjection.getData()));

    }
}
