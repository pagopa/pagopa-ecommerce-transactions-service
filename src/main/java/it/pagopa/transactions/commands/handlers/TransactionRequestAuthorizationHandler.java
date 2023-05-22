package it.pagopa.transactions.commands.handlers;

import com.azure.cosmos.implementation.BadRequestException;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData.PaymentGateway;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class TransactionRequestAuthorizationHandler
        implements CommandHandler<TransactionRequestAuthorizationCommand, Mono<RequestAuthorizationResponseDto>> {

    private final PaymentGatewayClient paymentGatewayClient;

    private final TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;
    private final TransactionsUtils transactionsUtils;

    private final Map<CardAuthRequestDetailsDto.BrandEnum, URI> cardBrandLogoMapping;

    @Autowired
    public TransactionRequestAuthorizationHandler(
            PaymentGatewayClient paymentGatewayClient,
            TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository,
            TransactionsUtils transactionsUtils,
            @Qualifier("brandConfMap") Map<CardAuthRequestDetailsDto.BrandEnum, URI> cardBrandLogoMapping
    ) {
        this.paymentGatewayClient = paymentGatewayClient;
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.transactionsUtils = transactionsUtils;
        this.cardBrandLogoMapping = cardBrandLogoMapping;
    }

    @Override
    public Mono<RequestAuthorizationResponseDto> handle(TransactionRequestAuthorizationCommand command) {
        URI logo = getLogo(command.getData().authDetails());
        Mono<BaseTransaction> transaction = transactionsUtils.reduceEvents(
                command.getData().transaction().getTransactionId()
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

        var monoPostePay = Mono.just(command.getData())
                .flatMap(
                        authorizationRequestData -> paymentGatewayClient
                                .requestPostepayAuthorization(authorizationRequestData)
                )
                .map(
                        postePayAuthResponseEntityDto -> Tuples.of(
                                postePayAuthResponseEntityDto.getRequestId(),
                                postePayAuthResponseEntityDto.getUrlRedirect(),
                                PaymentGateway.POSTEPAY
                        )
                );

        var monoXPay = Mono.just(command.getData())
                .flatMap(
                        authorizationRequestData -> paymentGatewayClient
                                .requestXPayAuthorization(authorizationRequestData)
                )
                .map(
                        xPayAuthResponseEntityDto -> Tuples.of(
                                xPayAuthResponseEntityDto.getRequestId(),
                                xPayAuthResponseEntityDto.getUrlRedirect(),
                                PaymentGateway.XPAY
                        )
                );

        var monoVPOS = Mono.just(command.getData())
                .flatMap(
                        authorizationRequestData -> paymentGatewayClient
                                .requestCreditCardAuthorization(authorizationRequestData)
                )
                .map(
                        creditCardAuthResponseDto -> Tuples.of(
                                creditCardAuthResponseDto.getRequestId(),
                                creditCardAuthResponseDto.getUrlRedirect(),
                                PaymentGateway.VPOS
                        )
                );

        List<Mono<Tuple3<String, String, PaymentGateway>>> gatewayRequests = List.of(monoPostePay, monoXPay, monoVPOS);

        Mono<Tuple3<String, String, PaymentGateway>> gatewayAttempts = gatewayRequests
                .stream()
                .reduce(
                        (
                         pipeline,
                         candidateStep
                        ) -> pipeline.switchIfEmpty(candidateStep)
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
                                    String cardBrand = null;
                                    if (command.getData()
                                            .authDetails()instanceof CardAuthRequestDetailsDto detailType) {
                                        cardBrand = detailType.getBrand().getValue();
                                    }
                                    TransactionAuthorizationRequestedEvent authorizationEvent = new TransactionAuthorizationRequestedEvent(
                                            t.getTransactionId().value().toString(),
                                            new TransactionAuthorizationRequestData(
                                                    command.getData().transaction().getPaymentNotices().stream()
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
                                                    tuple3.getT1(),
                                                    tuple3.getT3(),
                                                    logo,
                                                    cardBrand
                                            )
                                    );

                                    return transactionEventStoreRepository.save(authorizationEvent)
                                            .thenReturn(tuple3)
                                            .map(
                                                    auth -> new RequestAuthorizationResponseDto()
                                                            .authorizationUrl(tuple3.getT2())
                                                            .authorizationRequestId(tuple3.getT1())
                                            );
                                })
                                .doOnError(BadRequestException.class, error -> log.error(error.getMessage()))
                );
    }

    private URI getLogo(RequestAuthorizationRequestDetailsDto authRequestDetails) {
        URI logoURI = null;
        if (authRequestDetails instanceof CardAuthRequestDetailsDto cardDetail) {
            CardAuthRequestDetailsDto.BrandEnum cardBrand = cardDetail.getBrand();
            logoURI = cardBrandLogoMapping.get(cardBrand);
        }
        // TODO handle different methods than cards
        return logoURI;
    }
}
