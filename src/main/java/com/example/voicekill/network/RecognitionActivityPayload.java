package com.example.voicekill.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;

/** Activity only: no transcript, danger-word matching or frequency-statistics input. */
public record RecognitionActivityPayload(String session) implements CustomPacketPayload {
    public static final Type<RecognitionActivityPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("voicekill","recognition_activity"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RecognitionActivityPayload> CODEC=StreamCodec.of(RecognitionActivityPayload::encode,RecognitionActivityPayload::decode);
    public RecognitionActivityPayload {session=UUID.fromString(session).toString();}
    private static void encode(RegistryFriendlyByteBuf buf,RecognitionActivityPayload payload){buf.writeUtf(payload.session,36);}
    private static RecognitionActivityPayload decode(RegistryFriendlyByteBuf buf){return new RecognitionActivityPayload(buf.readUtf(36));}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
