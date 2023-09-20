package it.pagopa.transactions.commands.handlers.v2;

import com.azure.cosmos.implementation.BadRequestException;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData.PaymentGateway;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent;
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
import it.pagopa.transactions.commands.handlers.CommandHandler;
import it.pagopa.transactions.commands.handlers.TransactionRequestAuthorizationHandlerCommon;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
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
public class TransactionRequestAuthorizationHandler extends TransactionRequestAuthorizationHandlerCommon
        implements CommandHandler<TransactionRequestAuthorizationCommand, Mono<RequestAuthorizationResponseDto>> {
    private final TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;
    private final TransactionsUtils transactionsUtils;

    private final EcommercePaymentMethodsClient paymentMethodsClient;

    @Autowired
    public TransactionRequestAuthorizationHandler(
            PaymentGatewayClient paymentGatewayClient,
            TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository,
            TransactionsUtils transactionsUtils,
            @Qualifier("brandConfMap") Map<CardAuthRequestDetailsDto.BrandEnum, URI> cardBrandLogoMapping,
            @Value("${checkout.basePath}") String checkoutBasePath,
            EcommercePaymentMethodsClient paymentMethodsClient
    ) {
        super(
                paymentGatewayClient,
                cardBrandLogoMapping,
                checkoutBasePath
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
                                    TransactionAuthorizationRequestData.CardBrand cardBrand = null;
                                    if (command.getData()
                                            .authDetails()instanceof CardAuthRequestDetailsDto detailType) {
                                        cardBrand = TransactionAuthorizationRequestData.CardBrand
                                                .valueOf(detailType.getBrand().getValue());
                                    }
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
                                                    logo,
                                                    cardBrand,
                                                    command.getData().paymentMethodDescription()
                                            )
                                    );

                                    Mono<Void> updateSession = Mono.just(command.getData().authDetails())
                                            .filter(CardsAuthRequestDetailsDto.class::isInstance)
                                            .cast(CardsAuthRequestDetailsDto.class)
                                            .flatMap(
                                                    authRequestDetails -> paymentMethodsClient.updateSession(
                                                            command.getData().paymentInstrumentId(),
                                                            authRequestDetails.getOrderId(),
                                                            t.getTransactionId().value()
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
