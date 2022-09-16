package it.pagopa.transactions.utils;

import it.pagopa.generated.nodoperpsp.model.*;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.CtQrCode;
import it.pagopa.generated.transactions.model.StOutcome;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NodoPerPspClient;
import it.pagopa.transactions.domain.IdempotencyKey;
import it.pagopa.transactions.domain.RptId;
import it.pagopa.transactions.exceptions.NodoErrorException;
import it.pagopa.transactions.repositories.PaymentRequestInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.Optional;

@Slf4j
@Component
public class NodoOperations {

  private static final String PSP_PAGOPA_ECOMMERCE_FISCAL_CODE = "00000000000";

  private static final String ALPHANUMERICS =
          "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final SecureRandom RANDOM = new SecureRandom();

  @Autowired NodoPerPspClient nodoPerPspClient;

  @Autowired NodeForPspClient nodeForPspClient;

  @Autowired ActivatePaymentNoticeReq baseActivatePaymentNoticeReq;

  @Autowired NodoAttivaRPT baseNodoAttivaRPT;

  @Autowired it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp;

  @Autowired it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;

  public Mono<PaymentRequestInfo> activatePaymentRequest(
          PaymentRequestInfo paymentRequestInfo,
          NewTransactionRequestDto newTransactionRequestDto) {

    RptId rptId = paymentRequestInfo.id();
    String paymentContextCode = newTransactionRequestDto.getPaymentContextCode();
    Boolean isNM3 = paymentRequestInfo.isNM3();
    Integer amount = newTransactionRequestDto.getAmount();
    String paTaxCode = paymentRequestInfo.paFiscalCode();
    String paName = paymentRequestInfo.paName();
    IdempotencyKey idempotencyKey = Optional.ofNullable(paymentRequestInfo.idempotencyKey())
            .orElseGet(
                    () ->
                            new IdempotencyKey(
                                    PSP_PAGOPA_ECOMMERCE_FISCAL_CODE, randomString(10)));
    String dueDate = paymentRequestInfo.dueDate();
    String description = paymentRequestInfo.description();

    final BigDecimal amountAsBigDecimal =
        BigDecimal.valueOf(amount / 100).setScale(2, RoundingMode.CEILING);
    final String fiscalCode = rptId.getFiscalCode();
    final String noticeCode = rptId.getNoticeId();

    return Mono.just(isNM3)
        .flatMap(
            validIsNM3 ->
                Boolean.TRUE.equals(validIsNM3)
                    ? nodoActivationForNM3PaymentRequest(
                        fiscalCode, noticeCode, amountAsBigDecimal, idempotencyKey.getKey())
                    : nodoActivationForUnknownPaymentRequest(
                        fiscalCode,
                        noticeCode,
                        amountAsBigDecimal,
                        idempotencyKey.getKey(),
                        paymentContextCode))
        .flatMap(
            paymentToken ->
                Mono.just(
                    new PaymentRequestInfo(
                        rptId,
                        paTaxCode,
                        paName,
                        description,
                        amount,
                        dueDate,
                        isNM3,
                        paymentToken,
                        idempotencyKey)));
  }

  private Mono<String> nodoActivationForNM3PaymentRequest(
      String fiscalCode, String noticeCode, BigDecimal amount, String idempotencyKey) {
    CtQrCode qrCode = new CtQrCode();
    qrCode.setFiscalCode(fiscalCode);
    qrCode.setNoticeNumber(noticeCode);
    ActivatePaymentNoticeReq request = baseActivatePaymentNoticeReq;
    request.setAmount(amount);
    request.setQrCode(qrCode);
    request.setIdempotencyKey(idempotencyKey);
    return nodeForPspClient
        .activatePaymentNotice(objectFactoryNodeForPsp.createActivatePaymentNoticeReq(request))
        .flatMap(
            activatePaymentNoticeRes ->
                StOutcome.OK.value().equals(activatePaymentNoticeRes.getOutcome().value())
                    ? Mono.just(activatePaymentNoticeRes.getPaymentToken())
                    : Mono.error(
                        new NodoErrorException(
                            activatePaymentNoticeRes.getFault().getFaultCode())));
  }

  private Mono<String> nodoActivationForUnknownPaymentRequest(
      String fiscalCode,
      String noticeCode,
      BigDecimal amount,
      String idempotencyKey,
      String paymentContextCode) {
    NodoAttivaRPT nodoAttivaRPTReq = baseNodoAttivaRPT;

    NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT();
    NodoTipoCodiceIdRPT.QrCode qrCodeVerificaRPT = new NodoTipoCodiceIdRPT.QrCode();
    qrCodeVerificaRPT.setCF(fiscalCode);
    qrCodeVerificaRPT.setCodIUV(noticeCode.substring(1));
    qrCodeVerificaRPT.setAuxDigit(noticeCode.substring(0, 1));
    nodoTipoCodiceIdRPT.setQrCode(qrCodeVerificaRPT);
    NodoTipoDatiPagamentoPSP datiPagamentoPsp =
        objectFactoryNodoPerPsp.createNodoTipoDatiPagamentoPSP();
    datiPagamentoPsp.setImportoSingoloVersamento(amount);
    nodoAttivaRPTReq.setDatiPagamentoPSP(datiPagamentoPsp);
    nodoAttivaRPTReq.setCodiceIdRPT(nodoTipoCodiceIdRPT);
    nodoAttivaRPTReq.setCodiceContestoPagamento(paymentContextCode);
    return nodoPerPspClient
        .attivaRPT(objectFactoryNodoPerPsp.createNodoAttivaRPT(nodoAttivaRPTReq))
        .flatMap(
            nodoAttivaRPTResponse -> {
              final EsitoNodoAttivaRPTRisposta nodoAttivaRPTRResponse =
                  nodoAttivaRPTResponse.getNodoAttivaRPTRisposta();
              final String outcome = nodoAttivaRPTRResponse.getEsito();
              final Boolean isNM3GivenAttivaRPTRisposta =
                  StOutcome.KO.value().equals(outcome)
                      && "PPT_MULTI_BENEFICIARIO"
                          .equals(nodoAttivaRPTRResponse.getFault().getFaultCode());

              if (Boolean.TRUE.equals(isNM3GivenAttivaRPTRisposta)) {
                return nodoActivationForNM3PaymentRequest(
                    fiscalCode, noticeCode, amount, idempotencyKey);
              }

              return StOutcome.OK.value().equals(nodoAttivaRPTRResponse.getEsito())
                  ? Mono.just("")
                  : Mono.error(
                      new NodoErrorException(nodoAttivaRPTRResponse.getFault().getFaultCode()));
            });
  }

  public Integer getEuroCentsFromNodoAmount(BigDecimal amountFromNodo) {
    return amountFromNodo.multiply(BigDecimal.valueOf(100)).intValue();
  }

  private String randomString(int len) {
    StringBuilder sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      sb.append(ALPHANUMERICS.charAt(RANDOM.nextInt(ALPHANUMERICS.length())));
    }
    return sb.toString();
  }
}
