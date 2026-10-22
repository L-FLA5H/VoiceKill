package com.example.voicekill;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.damagesource.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeathMessageSourceTest {
    @Test void nativeDamageTypeIsPreservedAndOneSelectedMessageIsStable() {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var type=Holder.direct(new DamageType("genericKill",DamageScaling.NEVER,0f));
        var original=new DamageSource(type);var message=Component.literal("Alex 的自定义死亡文本");
        var custom=DeathMessages.source(original,message);message.append(" changed");
        assertSame(original.typeHolder(),custom.typeHolder());assertSame(original.type(),custom.type());
        assertEquals(original.scalesWithDifficulty(),custom.scalesWithDifficulty());
        assertEquals(original.getFoodExhaustion(),custom.getFoodExhaustion());
        assertNull(custom.getEntity());assertNull(custom.getDirectEntity());
        var first=custom.getLocalizedDeathMessage(null);((net.minecraft.network.chat.MutableComponent)first).append(" local change");
        assertEquals("Alex 的自定义死亡文本",custom.getLocalizedDeathMessage(null).getString());
        assertNotEquals(first.getString(),custom.getLocalizedDeathMessage(null).getString());
    }
}
