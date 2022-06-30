package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionTokenDto;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeRes;
import it.pagopa.generated.transactions.model.CtQrCode;
import it.pagopa.generated.transactions.model.ObjectFactory;
import it.pagopa.generated.transactions.model.StOutcome;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionInitializeCommand;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.documents.TransactionInitEvent;
import it.pagopa.transactions.domain.IdempotencyKey;
import it.pagopa.transactions.domain.RptId;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.repositories.TransactionTokens;
import it.pagopa.transactions.repositories.TransactionTokensRepository;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.NodoConnectionString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.UUID;

@Slf4j
@Component
public class TransactionInizializeHandler
        implements CommandHandler<TransactionInitializeCommand, Mono<NewTransactionResponseDto>> {

    private static final String ALPHANUMERICS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PSP_PAGOPA_ECOMMERCE_FISCAL_CODE = "00000000000";

    @Autowired
    private TransactionTokensRepository transactionTokensRepository;

    @Autowired
    private ObjectFactory objectFactory;

    @Autowired
    private NodeForPspClient nodeForPspClient;

    @Autowired
    private EcommerceSessionsClient ecommerceSessionsClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionInitData> transactionEventStoreRepository;

    @Autowired
    NodoConnectionString nodoConnectionParams;

    @Override
    public Mono<NewTransactionResponseDto> handle(TransactionInitializeCommand command) {
        final RptId rptId = command.getRptId();
        final NewTransactionRequestDto newTransactionRequestDto = command.getData();

        Mono<TransactionTokens> transactionTokens = Mono.fromCallable(() -> transactionTokensRepository
                .findById(command.getRptId())
                .orElseGet(() -> {
                    log.info("Creating new idempotency key for rptId {}", rptId);
                    final IdempotencyKey key = new IdempotencyKey(
                            PSP_PAGOPA_ECOMMERCE_FISCAL_CODE,
                            randomString(10));
                    final TransactionTokens tokens = new TransactionTokens(rptId, key, null);
                    return transactionTokensRepository.save(tokens);
                }));

        return transactionTokens
                .flatMap(tokens -> {
                    log.info("Transaction tokens for " + rptId + ": " + tokens);
                    String fiscalCode = rptId.getFiscalCode();
                    String noticeId = rptId.getNoticeId();

                    CtQrCode qrCode = new CtQrCode();
                    qrCode.setFiscalCode(fiscalCode);
                    qrCode.setNoticeNumber(noticeId);

                    BigDecimal amount = BigDecimal.valueOf(newTransactionRequestDto.getAmount() / 100).setScale(2,
                            RoundingMode.CEILING);

                    ActivatePaymentNoticeReq request = objectFactory.createActivatePaymentNoticeReq();
                    request.setAmount(amount);
                    request.setQrCode(qrCode);
                    request.setIdPSP(nodoConnectionParams.getIdPSP());
                    request.setIdChannel(nodoConnectionParams.getIdChannel());
                    request.setIdBrokerPSP(nodoConnectionParams.getIdBrokerPSP());
                    request.setPassword(nodoConnectionParams.getPassword());
                    request.setIdempotencyKey(tokens.idempotencyKey().getKey());
                    Mono<ActivatePaymentNoticeRes> activatePaymentNoticeResponse = nodeForPspClient
                            .activatePaymentNotice(objectFactory.createActivatePaymentNoticeReq(request));

                    return activatePaymentNoticeResponse.map(res -> Tuples.of(res, tokens.idempotencyKey()));
                })
                .flatMap(args -> {
                    final ActivatePaymentNoticeRes activatePaymentNoticeRes = args.getT1();
                    final StOutcome outcome = activatePaymentNoticeRes.getOutcome();
                    return StOutcome.KO.equals(outcome)
                            ? Mono.error(new BadGatewayException(activatePaymentNoticeRes.getFault().getFaultCode()))
                            : Mono.just(args);
                })
                .flatMap(args -> {
                    final ActivatePaymentNoticeRes activatePaymentNoticeRes = args.getT1();
                    final IdempotencyKey idempotencyKey = args.getT2();

                    log.info("Persisted transaction tokens for payment token {}",
                            activatePaymentNoticeRes.getPaymentToken());

                    final TransactionTokens tokens = new TransactionTokens(rptId, idempotencyKey,
                            activatePaymentNoticeRes.getPaymentToken());
                    return Mono.fromCallable(() -> transactionTokensRepository.save(tokens))
                            .thenReturn(activatePaymentNoticeRes);
                })
                .flatMap(activatePaymentNoticeRes -> {

                    final String transactionId = UUID.randomUUID().toString();

                    TransactionInitData data = new TransactionInitData();
                    data.setAmount(activatePaymentNoticeRes.getTotalAmount().intValue());
                    data.setDescription(activatePaymentNoticeRes.getPaymentDescription());
                    data.setEmail(newTransactionRequestDto.getEmail());

                    TransactionEvent<TransactionInitData> transactionInitializedEvent = new TransactionInitEvent(
                            transactionId,
                            newTransactionRequestDto.getRptId(),
                            activatePaymentNoticeRes.getPaymentToken(),
                            data);

                    log.info("Generated event TRANSACTION_INITIALIZED_EVENT for payment token {}",
                            activatePaymentNoticeRes.getPaymentToken());
                    return transactionEventStoreRepository.save(transactionInitializedEvent)
                            .thenReturn(transactionInitializedEvent);
                })
                .flatMap(transactionInitializedEvent -> {
                    SessionDataDto sessionRequest = new SessionDataDto()
                            .email(transactionInitializedEvent.getData().getEmail())
                            .paymentToken(transactionInitializedEvent.getPaymentToken())
                            .rptId(transactionInitializedEvent.getRptId());

                    Mono<SessionTokenDto> sessionToken = ecommerceSessionsClient.createSessionToken(sessionRequest);

                    return sessionToken.map(token -> Tuples.of(token, transactionInitializedEvent));
                })
                .map(args -> {
                    final SessionTokenDto sessionToken = args.getT1();
                    final TransactionEvent<TransactionInitData> transactionInitializedEvent = args.getT2();

                    return new NewTransactionResponseDto()
                            .amount(transactionInitializedEvent.getData().getAmount().intValue())
                            .reason(transactionInitializedEvent.getData().getDescription())
                            .authToken(sessionToken.getSessionToken())
                            .transactionId(transactionInitializedEvent.getTransactionId())
                            .paymentToken(transactionInitializedEvent.getPaymentToken())
                            .rptId(rptId.value());
                });
    }

    private String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHANUMERICS.charAt(RANDOM.nextInt(ALPHANUMERICS.length())));
        }
        return sb.toString();
    }

}
