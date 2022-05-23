package it.pagopa.transactions.data;

import it.pagopa.transactions.model.RptId;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@WritingConverter
public class RptIdWritingByteConverter implements Converter<RptId, byte[]> {
    @Override
    public byte[] convert(RptId source) {
        return source.value().getBytes(StandardCharsets.UTF_8);
    }
}

