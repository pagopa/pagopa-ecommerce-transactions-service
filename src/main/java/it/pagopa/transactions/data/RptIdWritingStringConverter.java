package it.pagopa.transactions.data;

import it.pagopa.transactions.model.RptId;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.stereotype.Component;

@Component
@WritingConverter
public class RptIdWritingStringConverter implements Converter<RptId, String> {
    @Override
    public String convert(RptId source) {
        return source.rptId();
    }
}