package it.pagopa.transactions.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NodoConnectionString {

    String idPSP;
    String idChannel;
    String idChannelPayment;
    String idBrokerPSP;
    String password;
}
