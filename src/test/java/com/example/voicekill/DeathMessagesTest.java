package com.example.voicekill;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class DeathMessagesTest {
    @Test void presetsAndCustomPoolsStayIndependent() {
        var custom=List.of("{player} 的专属文案 {word}");
        assertEquals(custom,DeathMessages.pool("custom",custom));
        assertEquals(DeathMessages.defaults(),DeathMessages.pool("unknown",custom));
        assertNotEquals(DeathMessages.pool("classic",custom),DeathMessages.pool("friends",custom));
        assertEquals(20,DeathMessages.pool("friends",custom).size());
        for(String style:List.of("loot","voice","deadpan")) {
            var pool=DeathMessages.pool(style,custom);assertEquals(4,pool.size());
            assertTrue(DeathMessages.defaults().containsAll(pool));assertFalse(pool.contains(custom.getFirst()));
        }
        assertTrue(DeathMessages.pool("loot",custom).getFirst().contains("毕生积蓄"));
        assertTrue(DeathMessages.pool("voice",custom).getFirst().contains("声带"));
        assertTrue(DeathMessages.pool("deadpan",custom).getFirst().contains("古人云"));
    }
    @Test void placeholdersAreLiteralAndNotRecursivelyExpanded() {
        var player=Component.literal("Alex{word}$");
        var rendered=DeathMessages.render("{player} 说了 {word}；{player}；{unknown}",player,"{player}%s");
        assertEquals("Alex{word}$ 说了 {player}%s；Alex{word}$；{unknown}",rendered.getString());
        player.append(" changed");assertFalse(rendered.getString().contains("changed"));
    }
    @Test void dangerousWordIsPinkWithoutChangingText() {
        var message=DeathMessages.render("{player} 说了「{word}」。",Component.literal("Alex"),"qaq");
        assertEquals("Alex 说了「qaq」。",message.getString());
        assertTrue(message.getSiblings().stream().anyMatch(c->c.getString().equals("qaq")&&c.getStyle().getColor()!=null&&c.getStyle().getColor().getValue()==0xFF77C8));
    }
    @Test void emptyTemplatesFallBackAndValidCustomPoolIsUsed() {
        assertEquals(DeathMessages.defaults(),DeathMessages.sanitize(Arrays.asList(null," ","\n")));
        var custom=List.of("{player} A {word}","{player} B {word}");var random=new Random(42);
        for(int i=0;i<20;i++)assertTrue(custom.contains(DeathMessages.choose(custom,random)));
        assertFalse(DeathMessages.choose(null,random).isBlank());
    }
    @Test void templatesAreBoundedDeduplicatedAndSingleLineWithoutSplittingEmoji() {
        assertEquals(List.of("A B C"),DeathMessages.sanitize(List.of(" A\nB\tC ","A B C")));
        assertEquals(DeathMessages.MAX_TEMPLATES,DeathMessages.sanitize(IntStream.range(0,100).mapToObj(i->"template "+i).toList()).size());
        String text=DeathMessages.sanitize(List.of("😀".repeat(300))).getFirst();
        assertEquals(DeathMessages.MAX_LENGTH,text.codePointCount(0,text.length()));assertFalse(text.contains("�"));
    }
}
