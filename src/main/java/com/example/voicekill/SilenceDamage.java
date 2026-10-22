package com.example.voicekill;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

/** Dedicated damage type; its tags make the configured amount/interval work with armor and cooldowns. */
public final class SilenceDamage {
    private static final ResourceKey<DamageType> TYPE=ResourceKey.create(Registries.DAMAGE_TYPE,Identifier.fromNamespaceAndPath("voicekill","silence"));
    private SilenceDamage() {}
    public static DamageSource source(ServerPlayer player){return new DamageSource(player.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(TYPE));}
}
