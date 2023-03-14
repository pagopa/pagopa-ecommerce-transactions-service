package it.pagopa.transactions.utils;

import io.vavr.control.Either;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import org.apache.commons.codec.binary.Base64;
import org.springframework.stereotype.Component;
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

    public Either<InvalidRequestException, UUID> uuidFromBase64(String str) {
        try {
            byte[] bytes = Base64.decodeBase64(str);
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            return Either.right(new UUID(bb.getLong(), bb.getLong()));
        } catch (BufferUnderflowException e) {
            return Either.left(new InvalidRequestException("Error while decode transactionId"));
        }
    }

}
