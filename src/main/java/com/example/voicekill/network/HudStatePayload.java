package com.example.voicekill.network;

import com.example.voicekill.DeathBoard;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：屏幕四角要显示的东西。
 *
 * 只发「显示用」的数据，客户端不做任何判定 —— 判定永远在服务端，
 * 不然改客户端就能免死。
 */
public record HudStatePayload(
        boolean running,
        List<String> words,
        int remainingSeconds,
        int intervalSeconds,
        List<String> boardNames,
        List<Integer> boardCounts,
        int totalDeaths
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<HudStatePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("voicekill", "hud"));

    private static final int MAX_WORDS = 20;
    private static final int MAX_BOARD = 8;

    public static final StreamCodec<RegistryFriendlyByteBuf, HudStatePayload> CODEC =
            StreamCodec.of(HudStatePayload::encode, HudStatePayload::decode);

    public static HudStatePayload of(boolean running, List<String> words, int remaining, int interval,
                                     List<DeathBoard.Entry> board, int totalDeaths) {
        List<String> names = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (DeathBoard.Entry entry : board) {
            if (names.size() >= MAX_BOARD) break;
            names.add(entry.name());
            counts.add(entry.count());
        }
        return new HudStatePayload(running, List.copyOf(words), remaining, interval,
                List.copyOf(names), List.copyOf(counts), totalDeaths);
    }

    private static void encode(RegistryFriendlyByteBuf buf, HudStatePayload payload) {
        buf.writeBoolean(payload.running);
        int wordCount = Math.min(payload.words.size(), MAX_WORDS);
        buf.writeVarInt(wordCount);
        for (int i = 0; i < wordCount; i++) {
            buf.writeUtf(payload.words.get(i), 64);
        }
        buf.writeVarInt(payload.remainingSeconds);
        buf.writeVarInt(payload.intervalSeconds);

        int boardCount = Math.min(payload.boardNames.size(), MAX_BOARD);
        buf.writeVarInt(boardCount);
        for (int i = 0; i < boardCount; i++) {
            buf.writeUtf(payload.boardNames.get(i), 32);
            buf.writeVarInt(payload.boardCounts.get(i));
        }
        buf.writeVarInt(payload.totalDeaths);
    }

    private static HudStatePayload decode(RegistryFriendlyByteBuf buf) {
        boolean running = buf.readBoolean();
        int wordCount = buf.readVarInt();
        List<String> words = new ArrayList<>(wordCount);
        for (int i = 0; i < wordCount; i++) {
            words.add(buf.readUtf(64));
        }
        int remaining = buf.readVarInt();
        int interval = buf.readVarInt();

        int boardCount = buf.readVarInt();
        List<String> names = new ArrayList<>(boardCount);
        List<Integer> counts = new ArrayList<>(boardCount);
        for (int i = 0; i < boardCount; i++) {
            names.add(buf.readUtf(32));
            counts.add(buf.readVarInt());
        }
        int total = buf.readVarInt();
        return new HudStatePayload(running, words, remaining, interval, names, counts, total);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
