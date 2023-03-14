package it.pagopa.transactions.utils;

import it.pagopa.transactions.exceptions.InvalidRequestException;
import org.apache.commons.codec.binary.Base64;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.UUID;

@Component
public class UUIDUtils {

    public String uuidToBase64(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return Base64.encodeBase64URLSafeString(bb.array());
    }

    public Mono<UUID> uuidFromBase64(String str) {
        try {
            byte[] bytes = Base64.decodeBase64(str);
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            return Mono.just(new UUID(bb.getLong(), bb.getLong()));
        } catch (BufferUnderflowException e) {
            return Mono.error(new InvalidRequestException("Error while decode transactionId"));
        }
    }

}
