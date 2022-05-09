package it.pagopa.transactions.model;

import javax.xml.bind.annotation.XmlRootElement;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@XmlRootElement
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SoapEnvelope {

    private String header;
    private Object body;
}
