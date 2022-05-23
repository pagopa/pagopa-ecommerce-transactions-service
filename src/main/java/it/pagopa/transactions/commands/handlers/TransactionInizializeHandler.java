package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionTokenDto;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeRes;
import it.pagopa.generated.transactions.model.CtQrCode;
import it.pagopa.generated.transactions.model.ObjectFactory;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionsCommand;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.documents.TransactionInitEvent;
import it.pagopa.transactions.model.IdempotencyKey;
import it.pagopa.transactions.model.RptId;
import it.pagopa.transactions.repositories.TransactionTokens;
import it.pagopa.transactions.repositories.TransactionTokensRepository;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.security.SecureRandom;

@Slf4j
@Component
public class TransactionInizializeHandler
        implements CommandHandler<TransactionsCommand<NewTransactionRequestDto>, Object> {

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

    @Override
    public Mono<NewTransactionResponseDto> handle(TransactionsCommand<NewTransactionRequestDto> command) {
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

                    BigDecimal amount = BigDecimal.valueOf(1200);

                    ActivatePaymentNoticeReq request = objectFactory.createActivatePaymentNoticeReq();
                    request.setAmount(amount);
                    request.setQrCode(qrCode);
                    request.setIdPSP("6666");
                    request.setIdChannel("7777");
                    request.setIdBrokerPSP("8888");
                    request.setPassword("password");
                    request.setIdempotencyKey(tokens.idempotencyKey().getKey());
                    request.setPaymentNote(newTransactionRequestDto.getRptId());
                    Mono<ActivatePaymentNoticeRes> activatePaymentNoticeResponse = nodeForPspClient.activatePaymentNotice(objectFactory.createActivatePaymentNoticeReq(request));

                    return activatePaymentNoticeResponse.map(res -> Tuples.of(res, tokens.idempotencyKey()));
                })
                .flatMap(args -> {
                    final ActivatePaymentNoticeRes activatePaymentNoticeRes = args.getT1();
                    final IdempotencyKey idempotencyKey = args.getT2();

                    final TransactionTokens tokens = new TransactionTokens(rptId, idempotencyKey, activatePaymentNoticeRes.getPaymentToken());
                    return Mono.fromCallable(() -> transactionTokensRepository.save(tokens)).thenReturn(activatePaymentNoticeRes);
                })
                .flatMap(activatePaymentNoticeRes -> {
                    log.info("Persisted transaction tokens for payment token {}", activatePaymentNoticeRes.getPaymentToken());

                    TransactionInitData data = new TransactionInitData();
                    data.setAmount(activatePaymentNoticeRes.getTotalAmount().intValue());
                    data.setDescription(activatePaymentNoticeRes.getPaymentDescription());

                    TransactionEvent<TransactionInitData> transactionInitializedEvent = new TransactionInitEvent(
                            newTransactionRequestDto.getRptId(),
                            activatePaymentNoticeRes.getPaymentToken(),
                            data);

                    log.info("Generated event TRANSACTION_INITIALIZED_EVENT for payment token {}", activatePaymentNoticeRes.getPaymentToken());
                    return transactionEventStoreRepository.save(transactionInitializedEvent).thenReturn(activatePaymentNoticeRes);
                })
                .flatMap(activatePaymentNoticeRes -> {
                    SessionDataDto sessionRequest = new SessionDataDto()
                            .email(newTransactionRequestDto.getEmail())
                            .paymentToken(activatePaymentNoticeRes.getPaymentToken())
                            .rptId(newTransactionRequestDto.getRptId());

                    Mono<SessionTokenDto> sessionToken = ecommerceSessionsClient.createSessionToken(sessionRequest);

                    return sessionToken.map(token -> Tuples.of(token, activatePaymentNoticeRes));
                })
                .map(args -> {
                    SessionTokenDto sessionToken = args.getT1();
                    ActivatePaymentNoticeRes activatePaymentNoticeRes = args.getT2();

                    return new NewTransactionResponseDto()
                            .amount(activatePaymentNoticeRes.getTotalAmount().intValue())
                            .reason(activatePaymentNoticeRes.getPaymentDescription())
                            .authToken(sessionToken.getSessionToken())
                            .paymentToken(activatePaymentNoticeRes.getPaymentToken())
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
