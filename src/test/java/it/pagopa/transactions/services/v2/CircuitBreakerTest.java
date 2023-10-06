package it.pagopa.transactions.services.v2;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.model.CtFaultBean;
import it.pagopa.generated.transactions.server.model.ClientIdDto;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.PartyConfigurationFaultDto;
import it.pagopa.generated.transactions.server.model.PaymentNoticeInfoDto;
import it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler;
import it.pagopa.transactions.exceptions.InvalidNodoResponseException;
import it.pagopa.transactions.exceptions.NodoErrorException;
import it.pagopa.transactions.services.v1.TransactionsService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.EMAIL_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-tests.properties")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CircuitBreakerTest {
    @Autowired
    private TransactionsService transactionsService;

    @MockBean
    private TransactionActivateHandler transactionActivateHandlerV2;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Test
    @Order(1)
    void shouldNotOpenCircuitBreakerForNodoErrorException() {
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_CCP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(10));

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "dest",
                                        0,
                                        TEST_CCP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0, null)),
                                        false
                                )
                        )
                );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                new TransactionId(TRANSACTION_ID).value(),
                transactionActivatedData
        );

        /*
         * Preconditions
         */
        CtFaultBean ctFaultBean = faultBeanWithCode(
                PartyConfigurationFaultDto.PPT_STAZIONE_INT_PA_ERRORE_RESPONSE.getValue()
        );
        Mockito.when(transactionActivateHandlerV2.handle(any()))
                .thenReturn(Mono.error(new NodoErrorException(ctFaultBean)));

        StepVerifier
                .create(
                        transactionsService.newTransaction(
                                transactionRequestDto,
                                clientIdDto,
                                new TransactionId(transactionActivatedEvent.getTransactionId())
                        )
                )
                .expectError(NodoErrorException.class)
                .verify();
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("node-backend");
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

    }

    @Test
    @Order(2)
    void shouldOpenCircuitBreakerForNotExcludedException() {
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_CCP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(10));

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "dest",
                                        0,
                                        TEST_CCP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0, null)),
                                        false
                                )
                        )
                );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                new TransactionId(TRANSACTION_ID).value(),
                transactionActivatedData
        );

        /*
         * Preconditions
         */
        Mockito.when(transactionActivateHandlerV2.handle(any()))
                .thenReturn(Mono.error(new InvalidNodoResponseException("Invalid response received")));

        StepVerifier
                .create(
                        transactionsService.newTransaction(
                                transactionRequestDto,
                                clientIdDto,
                                new TransactionId(transactionActivatedEvent.getTransactionId())
                        )
                )
                .expectError(CallNotPermittedException.class)
                .verify();
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("node-backend");
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

    }

    private static CtFaultBean faultBeanWithCode(String faultCode) {
        CtFaultBean fault = new CtFaultBean();
        fault.setFaultCode(faultCode);
        return fault;
    }
}
