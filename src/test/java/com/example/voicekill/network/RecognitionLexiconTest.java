package com.example.voicekill.network;

import com.example.voicekill.speech.WordMatcher;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecognitionLexiconTest {
    @Test void sourcePrioritySurvivesAnIndependentOptionalPayload() {
        String session=java.util.UUID.randomUUID().toString(),round=java.util.UUID.randomUUID().toString();
        var p=RecognitionLexiconPayload.of(List.of(new WordMatcher.Word("我","highfreq",List.of()),new WordMatcher.Word("p老板","name",List.of()),new WordMatcher.Word("自定义词","random",List.of(),true)),session,round,true);
        assertEquals(session,p.session());assertEquals(round,p.round());assertTrue(p.running());
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {RecognitionLexiconPayload.CODEC.encode(buf,p);assertEquals(p,RecognitionLexiconPayload.CODEC.decode(buf));assertEquals(List.of(false,true,true),p.preferred());}finally{buf.release();}
    }
    @Test void malformedCountsAreRejectedBeforeAllocatingLists() {
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {buf.writeUtf(java.util.UUID.randomUUID().toString(),36);buf.writeUtf(java.util.UUID.randomUUID().toString(),36);buf.writeBoolean(true);buf.writeVarInt(100000);assertThrows(IllegalArgumentException.class,()->RecognitionLexiconPayload.CODEC.decode(buf));}finally{buf.release();}
    }
}
