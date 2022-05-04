// package it.pagopa.transactions.utils.soap;

// import org.reactivestreams.Publisher;
// import org.springframework.core.ResolvableType;
// import org.springframework.core.codec.CodecException;
// import org.springframework.core.codec.Decoder;
// import org.springframework.core.codec.DecodingException;
// import org.springframework.core.io.buffer.DataBuffer;
// import org.springframework.core.io.buffer.DataBufferUtils;
// import org.springframework.core.io.buffer.PooledDataBuffer;
// import org.springframework.http.codec.xml.Jaxb2XmlDecoder;
// import org.springframework.lang.Nullable;
// import org.springframework.util.ClassUtils;
// import org.springframework.util.MimeType;
// import org.springframework.util.MimeTypeUtils;
// import org.springframework.util.xml.StaxUtils;
// import org.springframework.ws.InvalidXmlException;
// import org.springframework.ws.WebServiceMessage;
// import org.springframework.ws.WebServiceMessageFactory;
// import org.springframework.ws.client.core.WebServiceTemplate;
// import org.springframework.ws.support.DefaultStrategiesHelper;
// import org.w3c.dom.Document;

// import reactor.core.Exceptions;
// import reactor.core.publisher.Flux;
// import reactor.core.publisher.Mono;

// import javax.xml.bind.JAXBElement;
// import javax.xml.bind.JAXBException;
// import javax.xml.bind.MarshalException;
// import javax.xml.bind.UnmarshalException;
// import javax.xml.bind.Unmarshaller;
// import javax.xml.bind.annotation.XmlRootElement;
// import javax.xml.bind.annotation.XmlType;
// import javax.xml.parsers.DocumentBuilderFactory;
// import javax.xml.stream.XMLInputFactory;
// import javax.xml.stream.XMLStreamException;

// import java.io.IOException;
// import java.util.Arrays;
// import java.util.List;
// import java.util.Map;

// public class Jaxb2SoapDecoder implements Decoder<Object> {

//     private final JaxbContextContainer jaxbContexts = new JaxbContextContainer();

//     @Override
//     public boolean canDecode(ResolvableType elementType, MimeType mimeType) {
//         Class<?> outputClass = elementType.toClass();
//         return (outputClass.isAnnotationPresent(XmlRootElement.class) ||
//                 outputClass.isAnnotationPresent(XmlType.class));
//     }

//     @Override
//     public Object decode(DataBuffer buffer, ResolvableType elementType, MimeType mimeType,
//             Map<String, Object> hints) {

//         Object result = null;
//         try {

//             DefaultStrategiesHelper helper = new DefaultStrategiesHelper(WebServiceTemplate.class);
//             WebServiceMessageFactory messageFactory = helper.getDefaultStrategy(WebServiceMessageFactory.class);
//             WebServiceMessage message = messageFactory.createWebServiceMessage(buffer.asInputStream());

//             // Class<?> clazz = ClassUtils.getUserClass(soapEnvelopeRequest.getBody());
//             Unmarshaller marshaller = initUnmarshaller(SoapEnvelopeRequest.class);

//             System.out.println("test1dei test");
//             result = marshaller.unmarshal(message.getPayloadSource(), SoapEnvelopeRequest.class).getValue();
//         } catch (MarshalException ex) {
//             throw new DecodingException(
//                     "Could not unmarshal to XML", ex);
//         } catch (JAXBException ex) {
//             throw new CodecException("Invalid JAXB configuration", ex);
//         } catch (InvalidXmlException e) {
//             // TODO Auto-generated catch block
//             e.printStackTrace();
//         } catch (IOException e) {
//             // TODO Auto-generated catch block
//             e.printStackTrace();
//         }
//         return result;
//     }

//     @Override
//     public List<MimeType> getDecodableMimeTypes() {
//         return Arrays.asList(MimeTypeUtils.TEXT_XML, MimeTypeUtils.TEXT_HTML);
//     }

//     private Unmarshaller initUnmarshaller(Class<?> outputClass) throws CodecException, JAXBException {
//         return this.jaxbContexts.createUnmarshaller(outputClass);
//     }

//     @Override
//     public Flux<Object> decode(Publisher<DataBuffer> inputStream, ResolvableType elementType, MimeType mimeType,
//             Map<String, Object> hints) {
//         // TODO Auto-generated method stub
//         return null;
//     }

//     @Override
//     public Mono<Object> decodeToMono(Publisher<DataBuffer> inputStream, ResolvableType elementType, MimeType mimeType,
//             Map<String, Object> hints) {
//         // TODO Auto-generated method stub
//         return null;
//     }

// }
