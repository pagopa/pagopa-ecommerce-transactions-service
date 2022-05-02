package it.pagopa.transactions.data;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.convert.RedisCustomConversions;

import java.util.Arrays;

@Configuration
public class RedisConfiguration {
    @Bean
    public RedisCustomConversions redisCustomConversions(RptIdReadingByteConverter readingByteConverter,
                                                         RptIdWritingByteConverter writingByteConverter,
                                                         RptIdReadingStringConverter readingStringConverter,
                                                         RptIdWritingStringConverter writingStringConverter) {
        return new RedisCustomConversions(
                Arrays.asList(
                        readingByteConverter,
                        writingByteConverter,
                        readingStringConverter,
                        writingStringConverter
                )
        );
    }
}
