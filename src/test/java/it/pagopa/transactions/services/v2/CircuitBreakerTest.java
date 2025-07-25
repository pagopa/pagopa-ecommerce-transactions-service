package it.pagopa.transactions.services.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.v2.PaymentToken;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.transactions.model.CtFaultBean;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDto;
import it.pagopa.generated.transactions.v2.server.model.ClientIdDto;
import it.pagopa.generated.transactions.v2.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.v2.server.model.PartyConfigurationFaultDto;
import it.pagopa.generated.transactions.v2.server.model.PaymentNoticeInfoDto;
import it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler;
import it.pagopa.transactions.exceptions.*;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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

    @MockitoBean
    private TransactionActivateHandler transactionActivateHandlerV2;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;
    private static final Long MOCK_AMOUNT = 100L;
    private static final JsonNode resilience4jConfiguration;

    private static final Map<String, Exception> exceptionMapper = Stream.of(
            new UnsatisfiablePspRequestException(
                    new PaymentToken(""),
                    RequestAuthorizationRequestDto.LanguageEnum.IT,
                    0
            ),
            new PaymentNoticeAllCCPMismatchException("rptId", true, true),
            new TransactionNotFoundException(""),
            new AlreadyProcessedException(
                    new TransactionId(it.pagopa.ecommerce.commons.v1.TransactionTestUtils.TRANSACTION_ID)
            ),
            new NotImplementedException(""),
            new InvalidRequestException(""),
            new TransactionAmountMismatchException(10L, 11L),
            new NodoErrorException(new CtFaultBean()),
            new InvalidNodoResponseException("")
    ).collect(Collectors.toMap(exception -> exception.getClass().getCanonicalName(), Function.identity()));

    static {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            resilience4jConfiguration = mapper.readTree(new File("./src/main/resources/application.yml"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Stream<Arguments> getIgnoredExceptionsForRetry(String retryInstanceName) {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(
                        resilience4jConfiguration
                                .get("resilience4j.retry")
                                .get("instances")
                                .get(retryInstanceName)
                                .get("ignoreExceptions")
                                .elements(),
                        Spliterator.ORDERED
                ),
                false
        )
                .map(ignoredException -> {
                    String exceptionName = ignoredException.asText();
                    return Arguments.of(
                            Optional
                                    .ofNullable(exceptionMapper.get(exceptionName))
                                    .orElseThrow(
                                            () -> new RuntimeException(
                                                    "Missing exception instance in test suite inside map `exceptionMapper` for class: %s"
                                            )
                                    ),
                            retryInstanceName
                    );
                }

                );
    }

    private static Stream<Arguments> getIgnoredExceptionForNewTransactionRetry() {
        return getIgnoredExceptionsForRetry("newTransaction");
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForNewTransactionRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_newTransactionRetry(
                                                                       Exception thrownException,
                                                                       String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_CCP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(MOCK_AMOUNT)
                );

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        it.pagopa.ecommerce.commons.v1.TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "desc",
                                        0L,
                                        TEST_CCP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0L, null)),
                                        false,
                                        null,
                                        null
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
        Mockito.when(transactionActivateHandlerV2.handle(any())).thenReturn(Mono.error(thrownException));
        StepVerifier
                .create(
                        transactionsService.newTransaction(
                                transactionRequestDto,
                                clientIdDto,
                                UUID.randomUUID(),
                                new TransactionId(transactionActivatedEvent.getTransactionId()),
                                UUID.randomUUID()
                        )
                )
                .expectError(thrownException.getClass())
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
    }

    @Test
    @Order(1)
    void shouldNotOpenCircuitBreakerForNodoErrorException() {
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_CCP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(MOCK_AMOUNT)
                );

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "dest",
                                        0L,
                                        TEST_CCP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0L, null)),
                                        false,
                                        null,
                                        null
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
                                UUID.randomUUID(),
                                new TransactionId(transactionActivatedEvent.getTransactionId()),
                                UUID.randomUUID()
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
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(MOCK_AMOUNT)
                );

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "dest",
                                        0L,
                                        TEST_CCP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0L, null)),
                                        false,
                                        null,
                                        null
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
                .thenReturn(Mono.error(new RuntimeException("Invalid response received")));

        StepVerifier
                .create(
                        transactionsService.newTransaction(
                                transactionRequestDto,
                                clientIdDto,
                                UUID.randomUUID(),
                                new TransactionId(transactionActivatedEvent.getTransactionId()),
                                UUID.randomUUID()
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
