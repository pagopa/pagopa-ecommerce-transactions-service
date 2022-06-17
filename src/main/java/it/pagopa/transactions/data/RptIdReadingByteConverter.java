package it.pagopa.transactions.data;

import it.pagopa.transactions.domain.RptId;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ReadingConverter
public class RptIdReadingByteConverter implements Converter<byte[], RptId> {
    @Override
    public RptId convert(byte[] source) {
        return new RptId(new String(source, StandardCharsets.UTF_8));
    }
}
