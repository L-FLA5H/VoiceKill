package com.example.voicekill.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import com.example.voicekill.speech.RecognitionHealth.Issue;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RecognitionHealthPayloadTest {
    @Test void everyFixedStatusCodeRoundTripsWithoutTranscriptOrDeviceInformation() {
        for(var issue:Issue.values()) {
            var payload=new RecognitionHealthPayload(UUID.randomUUID().toString(),issue);
            var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{RecognitionHealthPayload.CODEC.encode(buf,payload);assertTrue(buf.readableBytes()<40);assertEquals(payload,RecognitionHealthPayload.CODEC.decode(buf));assertEquals(0,buf.readableBytes());}finally{buf.release();}
        }
    }
    @Test void invalidSessionsAndOutOfRangeCodesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new RecognitionHealthPayload("bad",Issue.READY));
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{buf.writeUtf(UUID.randomUUID().toString(),36);buf.writeVarInt(999);assertThrows(IllegalArgumentException.class,()->RecognitionHealthPayload.CODEC.decode(buf));}finally{buf.release();}
    }
}
