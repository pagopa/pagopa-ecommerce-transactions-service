package it.pagopa.transactions.commands.handlers.v1;

import com.azure.cosmos.implementation.BadRequestException;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData.PaymentGateway;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.utils.JwtTokenUtils;
import it.pagopa.generated.transactions.server.model.CardsAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationOutput;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.handlers.TransactionRequestAuthorizationHandlerCommon;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionTemplateWrapper;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import javax.crypto.SecretKey;
import java.net.URI;
import java.util.List;

@Component("TransactionRequestAuthorizationHandlerV1")
@Slf4j
public class TransactionRequestAuthorizationHandler extends TransactionRequestAuthorizationHandlerCommon {

    private final TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;

    private final TransactionsUtils transactionsUtils;

    private final EcommercePaymentMethodsClient paymentMethodsClient;

    @Autowired
    public TransactionRequestAuthorizationHandler(
            PaymentGatewayClient paymentGatewayClient,
            TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository,
            TransactionsUtils transactionsUtils,
            @Value("${checkout.basePath}") String checkoutBasePath,
            @Value("${checkout.npg.gdi.url}") String checkoutNpgGdiUrl,
            @Value("${checkout.outcome.url}") String checkoutOutcomeUrl,
            EcommercePaymentMethodsClient paymentMethodsClient,
            TransactionTemplateWrapper transactionTemplateWrapper,
            JwtTokenUtils jwtTokenUtils,
            @Qualifier("ecommerceWebViewSigningKey") SecretKey ecommerceWebViewSigningKey,
            @Value("${npg.notification.jwt.validity.time}") int jwtWebViewValidityTimeInSeconds
    ) {
        super(
                paymentGatewayClient,
                checkoutBasePath,
                checkoutNpgGdiUrl,
                checkoutOutcomeUrl,
                transactionTemplateWrapper,
                jwtTokenUtils,
                ecommerceWebViewSigningKey,
                jwtWebViewValidityTimeInSeconds
        );
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.transactionsUtils = transactionsUtils;
        this.paymentMethodsClient = paymentMethodsClient;
    }

    public Mono<RequestAuthorizationResponseDto> handle(TransactionRequestAuthorizationCommand command) {
        AuthorizationRequestData authorizationRequestData = command.getData();
        URI logo = getLogo(command.getData());
        Mono<BaseTransaction> transaction = transactionsUtils.reduceEventsV1(
                command.getData().transactionId()
        );
        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .cast(BaseTransaction.class)
                .doOnNext(
                        t -> log.warn(
                                "Invalid state transition: requested authorization for transaction {} from status {}",
                                t.getTransactionId(),
                                t.getStatus()
                        )
                ).flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));
        Mono<TransactionActivated> transactionActivated = transaction
                .filter(t -> t.getStatus() == TransactionStatusDto.ACTIVATED)
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionActivated.class);

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> monoNpgCards = transactionActivated
                .flatMap(
                        tx -> npgAuthRequestPipeline(
                                authorizationRequestData,
                                null,
                                tx.getClientId().name(),
                                command.lang,
                                null
                        )
                )
                .map(authorizationOutput -> Tuples.of(authorizationOutput, PaymentGateway.NPG));
        List<Mono<Tuple2<AuthorizationOutput, PaymentGateway>>> gatewayRequests = List
                .of(monoNpgCards);
        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> gatewayAttempts = gatewayRequests
                .stream()
                .reduce(
                        Mono::switchIfEmpty
                ).orElse(Mono.empty());

        return transactionActivated
                .flatMap(t -> switch (command.getData().authDetails()) {
                    case CardsAuthRequestDetailsDto authRequestDetails -> paymentMethodsClient.updateSession(
                            command.getData().paymentInstrumentId(),
                            authRequestDetails.getOrderId(),
                            command.getData().transactionId().value()
                    ).thenReturn(t);
                    default -> Mono.just(t);
                })
                .flatMap(
                        t -> gatewayAttempts.switchIfEmpty(Mono.error(new BadRequestException("No gateway matched")))
                                .flatMap(authorizationOutputAndGateway -> {
                                    log.info(
                                            "Logging authorization event for transaction id {}",
                                            t.getTransactionId().value()
                                    );
                                    AuthorizationOutput authorizationOutput = authorizationOutputAndGateway.getT1();
                                    PaymentGateway paymentGateway = authorizationOutputAndGateway.getT2();
                                    TransactionAuthorizationRequestData.CardBrand cardBrand = TransactionAuthorizationRequestData.CardBrand
                                            .valueOf(authorizationRequestData.brand());
                                    TransactionAuthorizationRequestedEvent authorizationEvent = new TransactionAuthorizationRequestedEvent(
                                            t.getTransactionId().value(),
                                            new it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData(
                                                    t.getPaymentNotices().stream()
                                                            .mapToInt(
                                                                    paymentNotice -> paymentNotice.transactionAmount()
                                                                            .value()
                                                            ).sum(),
                                                    command.getData().fee(),
                                                    command.getData().paymentInstrumentId(),
                                                    command.getData().pspId(),
                                                    command.getData().paymentTypeCode(),
                                                    command.getData().brokerName(),
                                                    command.getData().pspChannelCode(),
                                                    command.getData().paymentMethodName(),
                                                    command.getData().pspBusinessName(),
                                                    command.getData().pspOnUs(),
                                                    authorizationOutput.authorizationId(),
                                                    paymentGateway,
                                                    logo,
                                                    cardBrand,
                                                    command.getData().paymentMethodDescription()
                                            )
                                    );

                                    return transactionEventStoreRepository.save(authorizationEvent)
                                                    .thenReturn(authorizationOutputAndGateway)
                                                    .map(
                                                            auth -> new RequestAuthorizationResponseDto()
                                                                    .authorizationUrl(
                                                                            authorizationOutput.authorizationUrl()
                                                                    )
                                                                    .authorizationRequestId(
                                                                            authorizationOutput.authorizationId()
                                                                    )
                                                    );
                                })
                                .doOnError(BadRequestException.class, error -> log.error(error.getMessage()))
                );
    }
}
