package com.example.voicekill.speech;

import java.util.HashMap;
import java.util.Map;

/** Server-tick deadlines; only recognized text resets them, never raw microphone volume. */
public final class RecognitionSilence {
    public record Rules(boolean enabled,float timeoutSeconds,float damageAmount,float intervalSeconds) {
        public Rules {
            timeoutSeconds=clamp(timeoutSeconds,5,600,20);
            damageAmount=clamp(damageAmount,.5f,20,2);
            intervalSeconds=clamp(intervalSeconds,.5f,60,4);
        }
        private static float clamp(float value,float min,float max,float fallback){return Float.isFinite(value)?Math.max(min,Math.min(max,value)):fallback;}
        public long timeoutTicks(){return Math.round(timeoutSeconds*20);}
        public long intervalTicks(){return Math.round(intervalSeconds*20);}
    }
    private static final class State {long nextDamage;State(long next){nextDamage=next;}}
    private final Map<String,State> players=new HashMap<>();
    private Rules rules=new Rules(false,20,2,4);
    public static boolean hasText(String text){return text!=null&&text.codePoints().anyMatch(Character::isLetterOrDigit);}
    public void configure(Rules next){if(!rules.equals(next)){rules=next;clear();}}
    public Rules rules(){return rules;}
    public void recognized(String player,long tick){if(rules.enabled())players.put(player,new State(tick+rules.timeoutTicks()));}
    public boolean step(String player,long tick,boolean eligible) {
        if(!rules.enabled()||!eligible){forget(player);return false;}
        var state=players.computeIfAbsent(player,ignored->new State(tick+rules.timeoutTicks()));
        if(tick<state.nextDamage)return false;
        // Never apply a burst of catch-up damage after lag or a configuration change.
        state.nextDamage=tick+rules.intervalTicks();return true;
    }
    public void forget(String player){players.remove(player);}
    public void clear(){players.clear();}
}
