package it.pagopa.transactions.services.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import it.pagopa.ecommerce.commons.domain.v2.PaymentToken;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.model.CtFaultBean;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static it.pagopa.ecommerce.commons.domain.v2.TransactionEventCode.TRANSACTION_ACTIVATED_EVENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@SpringBootTest
@TestPropertySource(
        locations = "classpath:application-tests.properties", properties = {
                "ecommerce.event.version=V1"
        }

)
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CircuitBreakerTest {
    @Autowired
    private TransactionsService transactionsService;

    @MockitoBean
    private TransactionsViewRepository transactionsViewRepository;
    @MockitoBean
    private TransactionsEventStoreRepository transactionsEventStoreRepository;
    @MockitoBean
    private TransactionsUtils transactionsUtils;
    @MockitoBean
    private TransactionUserCancelHandler transactionCancelHandlerV2;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    private static final JsonNode resilience4jConfiguration;

    private static final Map<String, Exception> exceptionMapper = Stream.of(
            new UnsatisfiablePspRequestException(
                    new PaymentToken(""),
                    RequestAuthorizationRequestDto.LanguageEnum.IT,
                    0
            ),
            new PaymentNoticeAllCCPMismatchException("rptId", true, true),
            new TransactionNotFoundException(""),
            new AlreadyProcessedException(new TransactionId(TransactionTestUtils.TRANSACTION_ID)),
            new NotImplementedException(""),
            new InvalidRequestException(""),
            new TransactionAmountMismatchException(10, 11),
            new NodoErrorException(new CtFaultBean()),
            new InvalidNodoResponseException(""),
            new PaymentMethodNotFoundException("paymentMethodId", "clientId"),
            new NpgNotRetryableErrorException("", HttpStatus.INTERNAL_SERVER_ERROR),
            new LockNotAcquiredException(
                    new TransactionId(TransactionTestUtils.TRANSACTION_ID),
                    new ExclusiveLockDocument("id", "holderName")
            )
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
                                                            .formatted(exceptionName)
                                            )
                                    ),
                            retryInstanceName
                    );
                }

                );
    }

    private static Stream<Arguments> getIgnoredExceptionForGetTransactionInfoRetry() {
        return getIgnoredExceptionsForRetry("getTransactionInfo");
    }

    private static Stream<Arguments> getIgnoredExceptionForGetTransactionOutcomeRetry() {
        return getIgnoredExceptionsForRetry("getTransactionOutcome");
    }

    private static Stream<Arguments> getIgnoredExceptionForRequestTransactionAuthorizationRetry() {
        return getIgnoredExceptionsForRetry("requestTransactionAuthorization");
    }

    private static Stream<Arguments> getIgnoredExceptionForUpdateTransactionAuthorizationRetry() {
        return getIgnoredExceptionsForRetry("updateTransactionAuthorization");
    }

    private static Stream<Arguments> getIgnoredExceptionForCancelTransactionRetry() {
        return getIgnoredExceptionsForRetry("cancelTransaction");
    }

    private static Stream<Arguments> getIgnoredExceptionForAddUserReceiptRetry() {
        return getIgnoredExceptionsForRetry("addUserReceipt");
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForGetTransactionInfoRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_getTransactionInfoRetry(
                                                                           Exception thrownException,
                                                                           String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;

        /*
         * Preconditions
         */
        Mockito.when(transactionsViewRepository.findById(any(String.class)))
                .thenReturn(Mono.error(thrownException));

        StepVerifier
                .create(
                        transactionsService.getTransactionInfo("transactionId", null)
                )
                .expectError(thrownException.getClass())
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForGetTransactionOutcomeRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_getTransactionOutcomeInfoRetry(
                                                                                  Exception thrownException,
                                                                                  String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;

        /*
         * Preconditions
         */
        Mockito.when(transactionsViewRepository.findById(any(String.class)))
                .thenReturn(Mono.error(thrownException));

        StepVerifier
                .create(
                        transactionsService.getTransactionOutcome("transactionId", null)
                )
                .expectError(thrownException.getClass())
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForRequestTransactionAuthorizationRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_requestTransactionAuthorizationRetry(
                                                                                        Exception thrownException,
                                                                                        String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;

        /*
         * Preconditions
         */
        Mockito.when(
                transactionsEventStoreRepository.findByTransactionIdAndEventCode(any(String.class), any(String.class))
        )
                .thenReturn(Mono.error(thrownException));

        StepVerifier
                .create(
                        transactionsService.requestTransactionAuthorization(
                                "transactionId",
                                null,
                                "",
                                null,
                                new RequestAuthorizationRequestDto()
                        )
                )
                .expectError(thrownException.getClass())
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForUpdateTransactionAuthorizationRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_updateTransactionAuthorizationRetry(
                                                                                       Exception thrownException,
                                                                                       String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;

        /*
         * Preconditions
         */

        Mockito.when(transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(any()))
                .thenReturn(Flux.error(thrownException));
        Mockito.when(transactionsUtils.reduceEvents(any(), any(), any(), any()))
                .thenReturn(Mono.error(thrownException));

        StepVerifier
                .create(
                        transactionsService
                                .updateTransactionAuthorization(UUID.randomUUID(), new UpdateAuthorizationRequestDto())
                )
                .expectError(thrownException.getClass())
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForCancelTransactionRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_cancelTransactionRetry(
                                                                          Exception thrownException,
                                                                          String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;

        /*
         * Preconditions
         */
        Mockito.when(
                transactionCancelHandlerV2.handle(any())
        ).thenReturn(Mono.error(thrownException));

        StepVerifier
                .create(
                        transactionsService.cancelTransaction(UUID.randomUUID().toString().replaceAll("-", ""), null)
                )
                .expectError(thrownException.getClass())
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForAddUserReceiptRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_AddUserReceiptRetry(
                                                                       Exception thrownException,
                                                                       String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;

        /*
         * Preconditions
         */
        Mockito.when(
                transactionsEventStoreRepository.findByTransactionIdAndEventCode(
                        any(String.class),
                        eq(TRANSACTION_ACTIVATED_EVENT.toString())
                )
        ).thenReturn(Mono.error(thrownException));
        StepVerifier
                .create(
                        transactionsService.addUserReceipt("", new AddUserReceiptRequestDto())
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
    void shouldPerformRetryForInvalidStatusExceptionOnAddUserReceipt() {
        Retry retry = retryRegistry.retry("addUserReceipt");
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt();
        long expectedFailedCallsWithRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt() + 1;

        /*
         * Preconditions
         */
        Mockito.when(
                transactionsEventStoreRepository.findByTransactionIdAndEventCode(any(String.class), any(String.class))
        )
                .thenReturn(Mono.error(new InvalidStatusException("Error processing request")));

        StepVerifier
                .create(
                        transactionsService.addUserReceipt("", new AddUserReceiptRequestDto())
                )
                .expectError(InvalidStatusException.class)
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
        assertEquals(expectedFailedCallsWithRetryAttempt, retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt());

    }
}
