package it.pagopa.transactions.utils;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.storage.queue.models.SendMessageResult;
import reactor.core.publisher.Mono;

public class Queues {
    public static Mono<SendMessageResult> QUEUE_SUCCESSFUL_RESULT = Mono.just(new SendMessageResult());

    public static Mono<Response<SendMessageResult>> QUEUE_SUCCESSFUL_RESPONSE = Mono.just(new Response<>() {
        @Override
        public int getStatusCode() {
            return 200;
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders();
        }

        @Override
        public HttpRequest getRequest() {
            return null;
        }

        @Override
        public SendMessageResult getValue() {
            return new SendMessageResult();
        }
    });
}
