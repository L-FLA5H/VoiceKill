package com.example.voicekill.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class HudStatePayloadTest {
    @Test void allTwentyDangerWordsSurviveNetworkEncoding() {
        var words = IntStream.range(0, 20).mapToObj(index -> "危险词" + index).toList();
        var payload = HudStatePayload.of(true, words, 15, 30, List.of(), 0);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            HudStatePayload.CODEC.encode(buffer, payload);
            var decoded = HudStatePayload.CODEC.decode(buffer);
            assertEquals(words, decoded.words());
            assertEquals(15, decoded.remainingSeconds());
        } finally { buffer.release(); }
    }
}
