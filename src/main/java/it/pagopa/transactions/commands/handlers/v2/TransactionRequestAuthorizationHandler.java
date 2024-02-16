package it.pagopa.transactions.commands.handlers.v2;

import com.azure.cosmos.implementation.BadRequestException;
import com.azure.cosmos.implementation.InternalServerErrorException;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData.PaymentGateway;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.PgsTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.TransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.transactions.server.model.ApmAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.CardsAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.generated.transactions.server.model.WalletAuthRequestDetailsDto;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationOutput;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.handlers.TransactionRequestAuthorizationHandlerCommon;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.repositories.TransactionTemplateWrapper;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.LogoMappingUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.net.URI;
import java.util.List;

@Component("TransactionRequestAuthorizationHandlerV2")
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
            EcommercePaymentMethodsClient paymentMethodsClient,
            LogoMappingUtils logoMappingUtils,
            TransactionTemplateWrapper transactionTemplateWrapper
    ) {
        super(
                paymentGatewayClient,
                checkoutBasePath,
                logoMappingUtils,
                transactionTemplateWrapper
        );
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.transactionsUtils = transactionsUtils;
        this.paymentMethodsClient = paymentMethodsClient;
    }

    @Override
    public Mono<RequestAuthorizationResponseDto> handle(TransactionRequestAuthorizationCommand command) {
        AuthorizationRequestData authorizationRequestData = command.getData();
        URI logo = getLogo(command.getData());
        Mono<BaseTransaction> transaction = transactionsUtils.reduceEventsV2(
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

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> monoXPay = xpayAuthRequestPipeline(
                authorizationRequestData
        ).map(
                authorizationOutput -> Tuples.of(
                        authorizationOutput,
                        PaymentGateway.XPAY
                )
        );

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> monoVPOS = vposAuthRequestPipeline(
                authorizationRequestData
        ).map(
                authorizationOutput -> Tuples.of(
                        authorizationOutput,
                        PaymentGateway.VPOS
                )
        );

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> monoNpgCards = transactionActivated
                .flatMap(
                        tx -> npgAuthRequestPipeline(
                                authorizationRequestData,
                                tx.getTransactionActivatedData()
                                        .getTransactionGatewayActivationData()instanceof NpgTransactionGatewayActivationData transactionGatewayActivationData
                                                ? transactionGatewayActivationData.getCorrelationId()
                                                : null
                        )

                ).map(
                        authorizationOutput -> Tuples.of(
                                authorizationOutput,
                                PaymentGateway.NPG
                        )
                );

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> monoRedirect = transaction
                .map(BaseTransaction::getClientId).flatMap(
                        clientId -> redirectionAuthRequestPipeline(
                                authorizationRequestData,
                                clientId
                        )
                ).map(
                        authorizationOutput -> Tuples.of(
                                authorizationOutput,
                                PaymentGateway.REDIRECT
                        )
                );

        List<Mono<Tuple2<AuthorizationOutput, PaymentGateway>>> gatewayRequests = List
                .of(monoXPay, monoVPOS, monoNpgCards, monoRedirect);

        Mono<Tuple2<AuthorizationOutput, PaymentGateway>> gatewayAttempts = gatewayRequests
                .stream()
                .reduce(
                        Mono::switchIfEmpty
                ).orElse(Mono.empty());

        return transactionActivated
                .flatMap(
                        t -> gatewayAttempts.switchIfEmpty(Mono.error(new BadRequestException("No gateway matched")))
                                .flatMap(authorizationOutputAndPaymentGateway -> {
                                    log.info(
                                            "Logging authorization event for transaction id {}",
                                            t.getTransactionId().value()
                                    );
                                    AuthorizationOutput authorizationOutput = authorizationOutputAndPaymentGateway
                                            .getT1();
                                    PaymentGateway paymentGateway = authorizationOutputAndPaymentGateway.getT2();
                                    String brand = authorizationRequestData.brand();
                                    TransactionGatewayAuthorizationRequestedData transactionGatewayAuthorizationRequestedData = switch (paymentGateway) {
                                        case VPOS, XPAY -> new PgsTransactionGatewayAuthorizationRequestedData(
                                                logo,
                                                PgsTransactionGatewayAuthorizationRequestedData.CardBrand.valueOf(brand)
                                        );
                                        case NPG -> new NpgTransactionGatewayAuthorizationRequestedData(
                                                logo,
                                                brand,
                                                authorizationRequestData
                                                        .authDetails() instanceof WalletAuthRequestDetailsDto
                                                        || authorizationRequestData
                                                                .authDetails() instanceof ApmAuthRequestDetailsDto
                                                                        ? authorizationOutput.npgSessionId()
                                                                                .orElseThrow(
                                                                                        () -> new InternalServerErrorException(
                                                                                                "Cannot retrieve session id for transaction"
                                                                                        )
                                                                                ) // build session id
                                                                        : authorizationRequestData.sessionId()
                                                                                .orElseThrow(
                                                                                        () -> new BadGatewayException(
                                                                                                "Cannot retrieve session id for transaction",
                                                                                                HttpStatus.INTERNAL_SERVER_ERROR
                                                                                        )
                                                                                ),
                                                authorizationOutput.npgConfirmSessionId().orElse(null)
                                        );
                                        case REDIRECT -> new RedirectTransactionGatewayAuthorizationRequestedData(
                                                logo,
                                                authorizationOutput.authorizationId(),
                                                authorizationOutput.authorizationTimeoutMillis().orElse(600000),
                                                RedirectTransactionGatewayAuthorizationRequestedData.PaymentMethodType.BANK_ACCOUNT
                                        );
                                    };
                                    TransactionAuthorizationRequestedEvent authorizationEvent = new TransactionAuthorizationRequestedEvent(
                                            t.getTransactionId().value(),
                                            new TransactionAuthorizationRequestData(
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
                                                    command.getData().paymentMethodDescription(),
                                                    transactionGatewayAuthorizationRequestedData
                                            )
                                    );

                                    Mono<Void> updateSession = Mono.just(command.getData().authDetails())
                                            .filter(CardsAuthRequestDetailsDto.class::isInstance)
                                            .cast(CardsAuthRequestDetailsDto.class)
                                            .flatMap(
                                                    authRequestDetails -> paymentMethodsClient.updateSession(
                                                            command.getData().paymentInstrumentId(),
                                                            authRequestDetails.getOrderId(),
                                                            command.getData().transactionId().value()
                                                    )
                                            );
                                    return updateSession.then(
                                            transactionEventStoreRepository.save(authorizationEvent)
                                                    .thenReturn(authorizationOutput)
                                                    .map(
                                                            auth -> new RequestAuthorizationResponseDto()
                                                                    .authorizationUrl(
                                                                            authorizationOutput.authorizationUrl()
                                                                    )
                                                                    .authorizationRequestId(
                                                                            authorizationOutput.authorizationId()
                                                                    )
                                                    )
                                    );
                                })
                                .doOnError(BadRequestException.class, error -> log.error(error.getMessage()))
                );
    }

    /**
     * Redirection authorization pipeline
     *
     * @param authorizationData authorization data
     * @param clientId          client that initiated the transaction
     * @return a tuple of redirection url, psp authorization id and authorization
     *         timeout
     */
    protected Mono<AuthorizationOutput> redirectionAuthRequestPipeline(
                                                                       AuthorizationRequestData authorizationData,
                                                                       Transaction.ClientId clientId

    ) {
        Transaction.ClientId effectiveClient = switch (clientId) {
            case CHECKOUT_CART -> Transaction.ClientId.CHECKOUT;
            default -> clientId;
        };

        RedirectUrlRequestDto.TouchpointEnum touchpoint = RedirectUrlRequestDto.TouchpointEnum
                .valueOf(effectiveClient.name());

        return redirectionAuthRequestPipeline(authorizationData, touchpoint);
    }
}
