package it.pagopa.transactions.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.BAD_GATEWAY)
public class NodoErrorException extends RuntimeException {
  private final String faultCode;

  public NodoErrorException(String faultCode) {
    this.faultCode = faultCode;
  }

  public String getFaultCode() {
    return faultCode;
  }
}
