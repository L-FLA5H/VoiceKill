package com.example.voicekill.client;

import com.example.voicekill.util.TextUtil;
import java.util.ArrayList;
import java.util.List;

/** 句末音频复核与文字标点分离；原始首候选保留作依据/原文；接受的复核句用于判定，不向复核模型提供危险词。 */
public final class SentencePostprocessor implements AutoCloseable {
    public interface Reviewer extends AutoCloseable {
        String recognize(float[] audio) throws Exception;
        @Override void close();
    }
    public interface Punctuator extends AutoCloseable {
        String punctuate(String text) throws Exception;
        @Override void close();
    }
    public record Snapshot(String raw,String review,String punctuated,boolean accepted,String reason,
                           double reviewMs,double punctuationMs,String error,int passes) {}
    private Reviewer reviewer;
    private Punctuator punctuator;
    private boolean closed;
    private volatile boolean failed;
    public boolean failed(){return failed;}
    private volatile Snapshot snapshot=new Snapshot("","","",false,"waiting",0,0,"",0);
    public SentencePostprocessor(Reviewer reviewer,Punctuator punctuator) {this.reviewer=reviewer;this.punctuator=punctuator;}
    public Snapshot snapshot() {return snapshot;}

    public List<String> process(List<String> raw,float[] audio) {
        if(closed)throw new IllegalStateException("句末处理器已关闭");
        if(raw.isEmpty())return raw;
        String first="",second="",error="",reason="disabled";
        double reviewMs=0,punctuationMs=0;
        int passes=0;
        if(reviewer!=null) {
            long start=System.nanoTime();
            try {
                float[] shortTail=PcmUtterance.canonical(audio,16000);
                if(shortTail.length>0) {
                    first=reviewer.recognize(shortTail).trim();
                    passes=1;
                    reason=decision(raw,first,first);
                    if(reason.equals("accepted")) {
                        second=reviewer.recognize(PcmUtterance.canonical(audio,48000)).trim();passes=2;
                        reason=decision(raw,first,second);
                    }
                } else reason="no-audio";
            } catch(Exception|LinkageError failure) {
                failed=true;
                error=CaptureSession.rootCause(failure);reason="review-failed";
                try {reviewer.close();} catch(RuntimeException ignored) {} reviewer=null;
            }
            reviewMs=(System.nanoTime()-start)/1e6;
        }
        List<String> candidates=reason.equals("accepted") ? candidates(raw,first,second) : List.copyOf(raw);
        String display=reason.equals("accepted") ? first : raw.getFirst();
        String punctuated=display;
        if(punctuator!=null) {
            long start=System.nanoTime();
            try {
                String proposed=punctuator.punctuate(display).trim();
                if(!proposed.isEmpty()&&sameContent(display,proposed))punctuated=proposed;
                else error=error.isEmpty()?"标点结果改变正文，已保留原文":error;
            } catch(Exception|LinkageError failure) {
                failed=true;
                error=CaptureSession.rootCause(failure);
                try {punctuator.close();} catch(RuntimeException ignored) {} punctuator=null;
            }
            punctuationMs=(System.nanoTime()-start)/1e6;
        }
        snapshot=new Snapshot(raw.getFirst(),first,punctuated,reason.equals("accepted"),reason,reviewMs,punctuationMs,error,passes);
        return candidates;
    }

    public static List<String> candidates(List<String> raw,String first,String second) {
        if(!decision(raw,first,second).equals("accepted"))return List.copyOf(raw);
        var result=new ArrayList<>(raw);result.add(first.trim());return List.copyOf(result);
    }
    private static String decision(List<String> raw,String first,String second) {
        if(raw.isEmpty())return "no-primary";
        String a=TextUtil.normalize(first),b=TextUtil.normalize(second),original=TextUtil.normalize(raw.getFirst());
        if(a.isEmpty()||!a.equals(b))return "unstable";
        if(a.length()>512)return "too-long";
        if(raw.stream().anyMatch(text->TextUtil.normalize(text).equals(a)))return "unchanged";
        // The configured recognizer is Chinese/English. SenseVoice language detection
        // may turn a short Chinese syllable into kana; that is not a Chinese correction.
        if(supportedScript(original)&&!supportedScript(a))return "unsupported-script";
        if(com.example.voicekill.speech.GameTerms.retainPrimary(raw.getFirst(),first))return "primary-retained";
        if(raw.size()>=3)return "candidate-limit";
        int prior=letters(original),next=letters(a);
        if(prior!=0&&(prior&next)==0)return "conflicting-letters";
        return "accepted";
    }
    private static int letters(String value) {int bits=0;for(char c:value.toCharArray())if(c>='a'&&c<='z')bits|=1<<(c-'a');return bits;}
    private static boolean supportedScript(String value) {
        return value.codePoints().filter(Character::isLetter).allMatch(c->{
            var script=Character.UnicodeScript.of(c);
            return script==Character.UnicodeScript.HAN||script==Character.UnicodeScript.LATIN;
        });
    }
    private static String content(String value) {
        StringBuilder result=new StringBuilder();
        TextUtil.normalize(value).codePoints().filter(c->{int type=Character.getType(c);return type!=Character.CONNECTOR_PUNCTUATION
                &&type!=Character.DASH_PUNCTUATION&&type!=Character.START_PUNCTUATION&&type!=Character.END_PUNCTUATION
                &&type!=Character.INITIAL_QUOTE_PUNCTUATION&&type!=Character.FINAL_QUOTE_PUNCTUATION&&type!=Character.OTHER_PUNCTUATION;
        }).forEach(result::appendCodePoint);return result.toString();
    }
    public static boolean sameContent(String raw,String formatted) {return content(raw).equals(content(formatted));}
    @Override public void close() {
        if(closed)return;closed=true;
        try {if(reviewer!=null)reviewer.close();}finally{if(punctuator!=null)punctuator.close();}
    }
}
