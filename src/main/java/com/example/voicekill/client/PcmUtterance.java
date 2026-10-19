package com.example.voicekill.client;

/** 有界 PCM 历史；补齐静音只用于句末复核，不参与实时结算计时。 */
public final class PcmUtterance {
    private final float[] history;
    private int cursor, size;
    public PcmUtterance(int capacitySamples) {
        if(capacitySamples<1) throw new IllegalArgumentException("音频缓存不能为空");
        history=new float[capacitySamples];
    }
    public void append(byte[] pcm) {
        for(float sample:AudioCapture.pcmSamples(pcm)) {
            history[cursor]=sample;cursor=(cursor+1)%history.length;size=Math.min(size+1,history.length);
        }
    }
    public void keepLast(int samples) { size=Math.min(size,Math.max(0,samples)); }
    public void clear() { size=0;cursor=0; }
    public float[] samples() {
        float[] result=new float[size];int first=(cursor-size+history.length)%history.length;
        for(int i=0;i<size;i++)result[i]=history[(first+i)%history.length];
        return result;
    }
    public static float[] canonical(float[] audio,int tailSamples) {
        int first=-1,last=0;
        // 与冻结的回放语料相同：10ms RMS > -50dBFS，只规范前后静音，不改发音。
        for(int start=0;start<audio.length;start+=160) {
            int end=Math.min(start+160,audio.length);double energy=0;
            for(int i=start;i<end;i++)energy+=(double)audio[i]*audio[i];
            if(energy/(end-start)>1e-5) {if(first<0)first=start;last=end;}
        }
        if(first<0)return new float[0];
        float[] result=new float[4000+last-first+tailSamples];
        System.arraycopy(audio,first,result,4000,last-first);
        return result;
    }
}
