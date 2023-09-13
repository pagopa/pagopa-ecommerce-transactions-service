package it.pagopa.transactions.commands.handlers;

import com.azure.cosmos.implementation.BadRequestException;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData.PaymentGateway;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v1.TransactionId;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.CardsAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.implementation.bytecode.Throw;
import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class TransactionRequestAuthorizationHandler
        implements CommandHandler<TransactionRequestAuthorizationCommand, Mono<RequestAuthorizationResponseDto>> {

    private static final String CHECKOUT_GDI_CHECK_PATH = "/gdi-check#gdiIFrameUrl=";
    private static final String CHECKOUT_ESITO_PATH = "/esito";
    private final PaymentGatewayClient paymentGatewayClient;

    private final TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;
    private final TransactionsUtils transactionsUtils;

    private final String checkoutUri;

    private final Map<CardAuthRequestDetailsDto.BrandEnum, URI> cardBrandLogoMapping;

    private final EcommercePaymentMethodsClient paymentMethodsClient;

    @Autowired
    public TransactionRequestAuthorizationHandler(
            PaymentGatewayClient paymentGatewayClient,
            TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository,
            TransactionsUtils transactionsUtils,
            @Qualifier("brandConfMap") Map<CardAuthRequestDetailsDto.BrandEnum, URI> cardBrandLogoMapping,
            @Value("${checkout.uri}") String checkoutUri,
            EcommercePaymentMethodsClient paymentMethodsClient
    ) {
        this.paymentGatewayClient = paymentGatewayClient;
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.transactionsUtils = transactionsUtils;
        this.cardBrandLogoMapping = cardBrandLogoMapping;
        this.paymentMethodsClient = paymentMethodsClient;
        this.checkoutUri = checkoutUri;
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

        var monoNpgCards = Mono.just(command.getData())
                .flatMap(
                        authorizationRequestData -> paymentGatewayClient
                                .requestNpgCardsAuthorization(authorizationRequestData)
                )
                .map(
                        npgCardsResponseDto -> Tuples.of(
                                "sessionId",
                                switch (npgCardsResponseDto.getState()) {
                                case GDI_VERIFICATION -> URI.create(checkoutUri)
                                        .resolve(
                                                CHECKOUT_GDI_CHECK_PATH + Base64.encodeBase64URLSafeString(
                                                        npgCardsResponseDto.getFieldSet().getFields().get(0).getSrc()
                                                                .getBytes(StandardCharsets.UTF_8)
                                                )
                                        ).toString();
                                case REDIRECTED_TO_EXTERNAL_DOMAIN -> npgCardsResponseDto.getUrl();
                                case PAYMENT_COMPLETE -> URI.create(checkoutUri).resolve(CHECKOUT_ESITO_PATH)
                                        .toString();
                                default -> throw new BadGatewayException(
                                        "Invalid NPG confirm payment state response: " + npgCardsResponseDto.getState(),
                                        HttpStatus.BAD_GATEWAY
                                );
                                },
                                PaymentGateway.NPG
                        )

                );

        List<Mono<Tuple3<String, String, PaymentGateway>>> gatewayRequests = List
                .of(monoPostePay, monoXPay, monoVPOS, monoNpgCards);

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
                                    TransactionAuthorizationRequestData.CardBrand cardBrand = null;
                                    if (command.getData()
                                            .authDetails()instanceof CardAuthRequestDetailsDto detailType) {
                                        cardBrand = TransactionAuthorizationRequestData.CardBrand
                                                .valueOf(detailType.getBrand().getValue());
                                    }
                                    TransactionAuthorizationRequestedEvent authorizationEvent = new TransactionAuthorizationRequestedEvent(
                                            t.getTransactionId().value(),
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
                                                            authRequestDetails.getSessionId(),
                                                            command.getData().transaction().getTransactionId().value()
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
