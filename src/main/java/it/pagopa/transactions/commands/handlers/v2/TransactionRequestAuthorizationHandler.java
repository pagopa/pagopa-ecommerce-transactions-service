package it.pagopa.transactions.commands.handlers.v2;

import com.azure.cosmos.implementation.BadRequestException;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData.PaymentGateway;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.PgsTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.TransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.CardsAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.handlers.TransactionRequestAuthorizationHandlerCommon;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.LogoMappingUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.net.URI;
import java.util.List;
import java.util.Map;

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
            LogoMappingUtils logoMappingUtils
    ) {
        super(
                paymentGatewayClient,
                checkoutBasePath,
                logoMappingUtils
        );
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.transactionsUtils = transactionsUtils;
        this.paymentMethodsClient = paymentMethodsClient;
    }

    @Override
    public Mono<RequestAuthorizationResponseDto> handle(TransactionRequestAuthorizationCommand command) {
        AuthorizationRequestData authorizationRequestData = command.getData();
        URI logo = getLogo(command.getData().authDetails());
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

        Mono<Tuple3<String, String, PaymentGateway>> monoPostePay = postepayAuthRequestPipeline(
                authorizationRequestData
        )
                .map(tuple -> Tuples.of(tuple.getT1(), tuple.getT2(), PaymentGateway.POSTEPAY));
        Mono<Tuple3<String, String, PaymentGateway>> monoXPay = xpayAuthRequestPipeline(authorizationRequestData)
                .map(tuple -> Tuples.of(tuple.getT1(), tuple.getT2(), PaymentGateway.XPAY));
        Mono<Tuple3<String, String, PaymentGateway>> monoVPOS = vposAuthRequestPipeline(authorizationRequestData)
                .map(tuple -> Tuples.of(tuple.getT1(), tuple.getT2(), PaymentGateway.VPOS));
        Mono<Tuple3<String, String, PaymentGateway>> monoNpgCards = npgAuthRequestPipeline(authorizationRequestData)
                .map(tuple -> Tuples.of(tuple.getT1(), tuple.getT2(), PaymentGateway.NPG));
        List<Mono<Tuple3<String, String, PaymentGateway>>> gatewayRequests = List
                .of(monoPostePay, monoXPay, monoVPOS, monoNpgCards);

        Mono<Tuple3<String, String, PaymentGateway>> gatewayAttempts = gatewayRequests
                .stream()
                .reduce(
                        Mono::switchIfEmpty
                ).orElse(Mono.empty());

        return transactionActivated
                .flatMap(
                        t -> gatewayAttempts.switchIfEmpty(Mono.error(new BadRequestException("No gateway matched")))
                                .flatMap(tuple3 -> {
                                    log.info(
                                            "Logging authorization event for transaction id {}",
                                            t.getTransactionId().value()
                                    );

                                    // TODO remove this after the cancellation of the postepay logic
                                    PgsTransactionGatewayAuthorizationRequestedData.CardBrand cardBrand = getCardBrand(
                                            authorizationRequestData
                                    ).map(
                                            brand -> PgsTransactionGatewayAuthorizationRequestedData.CardBrand
                                                    .valueOf(brand.toString())
                                    ).orElse(null);
                                    TransactionGatewayAuthorizationRequestedData transactionGatewayAuthorizationRequestedData = switch (tuple3
                                            .getT3()) {
                                        case VPOS, XPAY -> new PgsTransactionGatewayAuthorizationRequestedData(
                                                logo,
                                                cardBrand
                                        );
                                        case NPG -> new NpgTransactionGatewayAuthorizationRequestedData(
                                                logo,z
                                                "" //TODO continua da qui, brand in questo caso è quello preso da risposta payment methods.
                                                //TODO forse conviene centralizzare il tutto lato service e prenderlo da una sola parte

                                        );
                                        // TODO remove this after the cancellation of the postepay logic
                                        case POSTEPAY -> null;
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
                                                    tuple3.getT1(),
                                                    tuple3.getT3(),
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
                                                    .thenReturn(tuple3)
                                                    .map(
                                                            auth -> new RequestAuthorizationResponseDto()
                                                                    .authorizationUrl(tuple3.getT2())
                                                                    .authorizationRequestId(tuple3.getT1())
                                                    )
                                    );
                                })
                                .doOnError(BadRequestException.class, error -> log.error(error.getMessage()))
                );
    }

}
