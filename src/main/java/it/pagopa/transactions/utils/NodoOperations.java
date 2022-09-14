package it.pagopa.transactions.utils;

import it.pagopa.generated.nodoperpsp.model.*;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.CtQrCode;
import it.pagopa.generated.transactions.model.StOutcome;
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

@Slf4j
@Component
public class NodoOperations {

  @Autowired NodoPerPspClient nodoPerPspClient;

  @Autowired NodeForPspClient nodeForPspClient;

  @Autowired ActivatePaymentNoticeReq baseActivatePaymentNoticeReq;

  @Autowired NodoAttivaRPT baseNodoAttivaRPT;

  @Autowired it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp;

  @Autowired it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;

  public Mono<PaymentRequestInfo> activatePaymentRequest(
      RptId rptId,
      String paymentContextCode,
      Boolean isNM3,
      Integer amount,
      String paTaxCode,
      String paName,
      IdempotencyKey idempotencyKey,
      String dueDate,
      String description) {

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
}
