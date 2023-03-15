package it.pagopa.transactions.utils;

import io.vavr.control.Either;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class UUIDUtilsTest {

    @InjectMocks
    UUIDUtils uuidUtils;

    @Test
    void shouldEncodeAndDecodeBase64OfUUID() {
        UUID uuid = UUID.randomUUID();

        String uuidAsBase64 = uuidUtils.uuidToBase64(uuid);
        Either<InvalidRequestException, UUID> uuidFromBase64 = uuidUtils.uuidFromBase64(uuidAsBase64);

        assertEquals(uuid, uuidFromBase64.get());
    }

    @Test
    void shouldDecodeBase64OfUUIDError() {
        String wrongUuid = "xxxx";
        Either<InvalidRequestException, UUID> uuidFromBase64 = uuidUtils.uuidFromBase64(wrongUuid);
        assertTrue(uuidFromBase64.isLeft());
        assertEquals(uuidFromBase64.getLeft().getMessage(), "Error while decode transactionId");
    }
}
