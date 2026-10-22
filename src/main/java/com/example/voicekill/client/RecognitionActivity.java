package com.example.voicekill.client;

import com.example.voicekill.util.TextUtil;

/** Rate-limited notices for fresh model text; repeated stale partials do not count. */
public final class RecognitionActivity {
    private String session="",previous="";
    private long lastSent,freshAt;
    private boolean sent,pending;
    public boolean observe(String sessionId,String text,boolean interim,long nowMillis) {
        if(!session.equals(sessionId)){session=sessionId;previous="";sent=false;pending=false;}
        StringBuilder content=new StringBuilder();
        TextUtil.normalize(text).codePoints().filter(Character::isLetterOrDigit).forEach(content::appendCodePoint);
        String current=content.toString();
        if(!current.isEmpty()&&(!interim||!current.equals(previous))){pending=true;freshAt=nowMillis;}
        previous=current;
        if(pending&&nowMillis-freshAt>1000)pending=false;
        if(!pending||(sent&&nowMillis-lastSent<1000))return false;
        pending=false;sent=true;lastSent=nowMillis;return true;
    }
}
