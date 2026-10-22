package com.example.voicekill.mixin;

import com.example.voicekill.client.HotbarHint;
import com.example.voicekill.config.ModConfig;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 仅接管 VoiceKill 自己的提示，其他模组/原版 actionbar 保持原样。 */
@Mixin(Gui.class)
public class VoiceKillActionBarMixin {
    @Inject(method="setOverlayMessage",at=@At("HEAD"),cancellable=true)
    private void voicekill$wrapHint(Component text,boolean animate,CallbackInfo ci) {
        ModConfig config=ModConfig.get();
        if(config!=null&&config.hudEnabled&&config.hudHotbarHint&&text.getString().startsWith("本轮危险词：")) {
            HotbarHint.show(text);ci.cancel();
        }
    }
}
