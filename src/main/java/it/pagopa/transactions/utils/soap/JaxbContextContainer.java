package it.pagopa.transactions.utils.soap;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

final class JaxbContextContainer {

    private static final String PACKAGE_NODE_FOR_PSP = "it.pagopa.generated.transactions.model";

    private static JAXBContext jaxbContext = null;

    public Marshaller createMarshaller() throws JAXBException {
        return getJaxbContext().createMarshaller();
    }

    public Unmarshaller createUnmarshaller() throws JAXBException {
        return getJaxbContext().createUnmarshaller();
    }

    private JAXBContext getJaxbContext() throws JAXBException {
        return jaxbContext  == null ? JAXBContext.newInstance(PACKAGE_NODE_FOR_PSP) : jaxbContext;
    }

}
