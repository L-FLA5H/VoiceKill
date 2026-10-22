package com.example.voicekill.network;

import com.example.voicekill.speech.RecognitionHealth.Issue;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;
import java.util.Objects;

/** Fixed status code only; no audio, transcript, device names or error details. */
public record RecognitionHealthPayload(String session,Issue issue) implements CustomPacketPayload {
    public static final Type<RecognitionHealthPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("voicekill","recognition_health"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RecognitionHealthPayload> CODEC=StreamCodec.of(RecognitionHealthPayload::encode,RecognitionHealthPayload::decode);
    public RecognitionHealthPayload {session=UUID.fromString(session).toString();Objects.requireNonNull(issue);}
    private static void encode(RegistryFriendlyByteBuf buf,RecognitionHealthPayload payload){buf.writeUtf(payload.session,36);buf.writeVarInt(payload.issue.ordinal());}
    private static RecognitionHealthPayload decode(RegistryFriendlyByteBuf buf){String session=buf.readUtf(36);int code=buf.readVarInt();if(code<0||code>=Issue.values().length)throw new IllegalArgumentException("无效语音状态");return new RecognitionHealthPayload(session,Issue.values()[code]);}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
