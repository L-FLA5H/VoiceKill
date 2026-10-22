package com.example.voicekill.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RecognitionActivityPayloadTest {
    @Test void activityContainsOnlyItsSessionAndRoundTrips() {
        var payload=new RecognitionActivityPayload(UUID.randomUUID().toString());var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{RecognitionActivityPayload.CODEC.encode(buf,payload);assertEquals(payload,RecognitionActivityPayload.CODEC.decode(buf));assertEquals(0,buf.readableBytes());}finally{buf.release();}
    }
    @Test void malformedSessionCannotBeAccepted() {
        assertThrows(IllegalArgumentException.class,()->new RecognitionActivityPayload("old-server"));
    }
}
