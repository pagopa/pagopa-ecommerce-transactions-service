package it.pagopa.transactions.utils;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UrlUtils {

    public static boolean urlsEqualsWithRandomParam(
                                                    String actual,
                                                    String expected
    ) {
        URI actualUri = URI.create(actual);
        URI expectedUri = URI.create(expected);

        // Compare full path
        assertEquals(actualUri.getHost(), expectedUri.getHost());
        assertEquals(actualUri.getPort(), expectedUri.getPort());
        assertEquals(actualUri.getPath(), expectedUri.getPath());

        // Compare fragments
        assertEquals(getParametersAsMap(actualUri.getFragment()), getParametersAsMap(expectedUri.getFragment()));
        // Compare query params without random, if are equals complete url is equals
        assertEquals(getParametersAsMap(actualUri.getQuery(), true), getParametersAsMap(expectedUri.getQuery(), true));
        return true;
    }

    public static Map<String, String> getParametersAsMap(String query) {
        return getParametersAsMap(query, false);
    }

    public static Map<String, String> getParametersAsMap(
                                                         String query,
                                                         boolean removeRandom
    ) {
        return Optional.ofNullable(query)
                .filter(q -> !q.isEmpty())
                .map(
                        q -> Arrays.stream(q.split("&"))
                                .map(param -> param.split("="))
                                // remove t queryparam
                                .filter(pair -> !removeRandom || !pair[0].equals("t"))
                                .collect(
                                        Collectors.toMap(
                                                pair -> pair[0],
                                                pair -> pair.length > 1 ? pair[1] : ""
                                        )
                                )
                )
                .orElse(Map.of());
    }
}
