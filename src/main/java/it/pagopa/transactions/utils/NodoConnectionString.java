package it.pagopa.transactions.utils;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class NodoConnectionString {

    String idPSP;
    String idChannel;
    String idBrokerPSP;
    String password;
}
