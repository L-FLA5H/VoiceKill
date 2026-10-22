package com.example.voicekill.speech;

import com.google.gson.Gson;
import com.example.voicekill.util.TextUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Whole Minecraft names, not a danger-word-biased spelling dictionary. */
public final class GameTerms {
    private record Catalog(List<String> terms) {}
    private record Term(String text,List<String> sounds) {}
    public record Match(String term,String evidence,int start,int end,int accent) {}
    private record Index(Map<Character,String> syllables,Set<String> literal,Map<String,List<Term>> folded) {}
    private static final CompletableFuture<Index> INDEX=new CompletableFuture<>();
    private static boolean started;
    private GameTerms() {}
    public static synchronized void warmUp() {
        if(started)return;started=true;
        Thread thread=new Thread(()->{try{INDEX.complete(load());}catch(Throwable failure){INDEX.completeExceptionally(failure);System.getLogger("VoiceKill").log(System.Logger.Level.WARNING,"Game vocabulary unavailable; strict matching remains active",failure);}},"VoiceKill-GameVocabulary");
        thread.setDaemon(true);thread.start();
    }
    public static void awaitReady(){warmUp();INDEX.join();}
    public static List<String> syllables(String text){warmUp();if(!INDEX.isDone()||INDEX.isCompletedExceptionally())return List.of();return sounds(INDEX.getNow(null).syllables(),text);}
    private static Index load() throws Exception {
        try(var resource=GameTerms.class.getResourceAsStream("/voicekill/game-terms-zh-cn.json")) {
            if(resource==null)throw new IOException("Missing Minecraft vocabulary");
            var terms=new Gson().fromJson(new InputStreamReader(resource,StandardCharsets.UTF_8),Catalog.class).terms();
            Map<Character,String> reader=new HashMap<>();
            try(var pronunciations=GameTerms.class.getResourceAsStream("/voicekill/pinyin-icu-77.1.json")) {
                if(pronunciations==null)throw new IOException("Missing pronunciation table");
                var data=com.google.gson.JsonParser.parseReader(new InputStreamReader(pronunciations,StandardCharsets.UTF_8)).getAsJsonObject();
                for(var entry:data.entrySet())reader.put(entry.getKey().charAt(0),entry.getValue().getAsString());
            }
            var folded=new HashMap<String,List<Term>>();
            for(String term:terms) {
                var sounds=sounds(reader,term);if(sounds.size()!=term.length())continue;
                folded.computeIfAbsent(key(sounds),ignored->new ArrayList<>()).add(new Term(term,sounds));
            }
            return new Index(reader,Set.copyOf(terms),folded);
        }
    }
    private static boolean han(char c){return c>=0x3400&&c<=0x9fff;}
    private static List<String> sounds(Map<Character,String> reader,String text) {
        var out=new ArrayList<String>();
        for(char c:text.toCharArray()) {
            if(!han(c))return List.of();
            String sound=reader.getOrDefault(c,"");
            if(sound.isEmpty())return List.of();out.add(sound);
        }
        return List.copyOf(out);
    }
    private static String fold(String value) {
        if(value.startsWith("l"))value="n"+value.substring(1);
        if(value.endsWith("ang")||value.endsWith("eng")||value.endsWith("ing"))value=value.substring(0,value.length()-1);
        return value;
    }
    private static String key(List<String> sounds){return String.join("/",sounds.stream().map(GameTerms::fold).toList());}
    private static int cost(List<String> a,List<String> b) {
        if(a.size()!=b.size())return 99;int changes=0;
        for(int i=0;i<a.size();i++) {
            String x=a.get(i),y=b.get(i);if(x.equals(y))continue;
            if(!fold(x).equals(fold(y)))return 99;
            // At most one n/l OR one final-nasal change over the entire word.
            boolean initial=(x.startsWith("n")&&y.startsWith("l"))||(x.startsWith("l")&&y.startsWith("n"));
            boolean nasal=x.endsWith("g")!=y.endsWith("g");changes+=(initial?1:0)+(nasal?1:0);
        }
        return changes;
    }
    private static Match resolve(Index index,String fragment,int offset,boolean accents) {
        if(index.literal().contains(fragment))return new Match(fragment,fragment,offset,offset+fragment.length(),0);
        var sounds=sounds(index.syllables(),fragment);if(sounds.isEmpty())return null;
        Match best=null;boolean ambiguous=false;
        for(var term:index.folded().getOrDefault(key(sounds),List.of())) {
            int difference=cost(sounds,term.sounds());if(difference>(accents?1:0))continue;
            int anchors=0;for(int i=0;i<fragment.length();i++)if(fragment.charAt(i)==term.text().charAt(i))anchors++;
            if(anchors<(difference==0?1:2))continue;
            if(best==null||difference<best.accent()){best=new Match(term.text(),fragment,offset,offset+fragment.length(),difference);ambiguous=false;}
            else if(difference==best.accent()&&!best.term().equals(term.text()))ambiguous=true;
        }
        return ambiguous?null:best;
    }
    public static List<Match> scan(String text,boolean accents) {
        warmUp();if(!INDEX.isDone()||INDEX.isCompletedExceptionally())return List.of();var index=INDEX.getNow(null);
        var found=new ArrayList<Match>();
        for(int start=0;start<text.length();) {
            if(!han(text.charAt(start))){start++;continue;}
            int end=start;while(end<text.length()&&han(text.charAt(end))&&end-start<24)end++;
            Match best=null;
            for(int size=end-start;size>=4;size--) {best=resolve(index,text.substring(start,start+size),start,accents);if(best!=null)break;}
            if(best==null)start++;else {found.add(best);start=best.end();}
        }
        return found;
    }
    private static boolean support(String primary,Match candidate,List<Match> raw) {
        if(raw.stream().anyMatch(m->m.term().equals(candidate.term())))return true;
        if(candidate.accent()!=0)return false;
        // A stable exact-sounding game name may repair a weak Chinese primary, but not
        // an unrelated English sentence or a completely unsupported hallucination.
        String target=candidate.term();var index=INDEX.getNow(null);
        if(index.literal().contains(primary)&&!primary.equals(target))return false;
        var expected=sounds(index.syllables(),target);
        for(int i=0;i+target.length()<=primary.length();i++) {
            String fragment=primary.substring(i,i+target.length());var actual=sounds(index.syllables(),fragment);
            if(index.literal().contains(fragment)&&!fragment.equals(target))continue;
            if(actual.size()!=expected.size())continue;int agree=0,anchors=0;
            for(int j=0;j<target.length();j++){if(actual.get(j).equals(expected.get(j)))agree++;if(fragment.charAt(j)==target.charAt(j))anchors++;}
            if(agree>=target.length()-2&&anchors>=1)return true;
        }
        return false;
    }
    /** Do not treat an incomplete second transcript as evidence that an already complete
     * isolated acronym/game noun was never spoken. This never invents a primary hit. */
    public static boolean retainPrimary(String primary,String review) {
        String a=content(primary),b=content(review);
        if(a.matches("[a-z]{3,8}")&&b.matches("[a-z]{2,7}")&&a.length()==b.length()+1&&a.startsWith(b))return true;
        warmUp();if(!INDEX.isDone()||INDEX.isCompletedExceptionally())return false;var index=INDEX.getNow(null);
        if(a.length()==1&&b.length()==1&&han(a.charAt(0))&&han(b.charAt(0))) {
            var x=sounds(index.syllables(),a);return !x.isEmpty()&&x.equals(sounds(index.syllables(),b));
        }
        if(a.length()<4||a.length()!=b.length()+1)return false;
        var complete=resolve(index,a,0,false);if(complete==null)return false;
        var x=sounds(index.syllables(),a);var y=sounds(index.syllables(),b);
        return x.size()==a.length()&&!y.isEmpty()&&x.subList(0,x.size()-1).equals(y);
    }
    private static String content(String value) {
        StringBuilder result=new StringBuilder();TextUtil.normalize(value).codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint);return result.toString();
    }
    public static List<Match> confirmed(String primary,String finalText,boolean reviewConfirmed) {
        String rawText=TextUtil.normalize(primary);var raw=scan(rawText,reviewConfirmed);
        var result=new ArrayList<Match>();
        for(var match:scan(finalText,reviewConfirmed)) {
            if(!reviewConfirmed&&match.accent()!=0)continue;
            if(reviewConfirmed&&!support(rawText,match,raw))continue;
            result.add(match);
        }
        return result;
    }
    public static WordMatcher.Hit find(List<WordMatcher.Word> words,String primary,String finalText,boolean reviewConfirmed) {
        String normalized=TextUtil.normalize(finalText);WordMatcher.Hit best=null;
        for(var match:confirmed(primary,normalized,reviewConfirmed))for(var word:words) {
            if("highfreq".equals(word.source())||"smart".equals(word.source())||!TextUtil.normalize(word.text()).equals(match.term()))continue;
            var hit=new WordMatcher.Hit(word.text(),"game-phonetic",match.evidence(),match.start(),match.end());
            if(WordMatcher.prefer(hit,best,words))best=hit;
        }
        return best;
    }
    public static String display(String primary,String finalText,boolean confirmed) {
        var matches=confirmed(primary,TextUtil.normalize(finalText),confirmed);if(matches.isEmpty())return finalText;
        // Match the same whitespace-normalized text as the server without removing
        // spaces from the surrounding English sentence or changing its original case.
        var positions=new ArrayList<Integer>();
        for(int i=0;i<finalText.length();i++)if(!Character.isWhitespace(finalText.charAt(i))&&!Character.isSpaceChar(finalText.charAt(i)))positions.add(i);
        var out=new StringBuilder();int end=0;
        for(var match:matches){out.append(finalText,end,positions.get(match.start())).append(match.term());end=positions.get(match.end()-1)+1;}
        return out.append(finalText,end,finalText.length()).toString();
    }
}
