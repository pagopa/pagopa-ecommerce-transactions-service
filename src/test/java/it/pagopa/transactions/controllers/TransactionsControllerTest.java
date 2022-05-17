package it.pagopa.transactions.controllers;

import it.pagopa.generated.transactions.server.model.BeneficiaryDto;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.services.TransactionsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class TransactionsControllerTest {

    @InjectMocks
    private TransactionsController transactionsController = new TransactionsController();

    @Mock
    private TransactionsService transactionsService;

    @Test
    public void shouldGetOk(){
       String RPTID = "77777777777302016723749670035";
       String EMAIL = "mario.rossi@email.com";

       NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto();
       newTransactionRequestDto.setRptId(RPTID);
       newTransactionRequestDto.setEmail(EMAIL);

       NewTransactionResponseDto response = new NewTransactionResponseDto();
       response.setAmount(10);
       response.setAuthToken("token");
       response.setBeneficiary(new BeneficiaryDto());
       response.setInstallments(new ArrayList<>());
       response.setReason("Reason");
       response.setRecipientIban("iban");
       response.setPaymentToken("payment_token");
       response.setRptId(RPTID);

        Mockito.lenient().when(transactionsService.newTransaction(newTransactionRequestDto)).thenReturn(Mono.just(response));

       ResponseEntity<NewTransactionResponseDto> responseEntity = transactionsController.newTransaction(Mono.just(newTransactionRequestDto), null).block();

       // Verify mock
       Mockito.verify(transactionsService, Mockito.times(1)).newTransaction(newTransactionRequestDto);

       // Verify status code and response
       assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
       assertEquals(response, responseEntity.getBody());
    }
}
