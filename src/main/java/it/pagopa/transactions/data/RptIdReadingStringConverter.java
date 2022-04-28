package it.pagopa.transactions.data;

import it.pagopa.transactions.model.RptId;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.stereotype.Component;

@Component
@ReadingConverter
public class RptIdReadingStringConverter implements Converter<String, RptId> {
    @Override
    public RptId convert(String source) {
        return new RptId(source);
    }
}
