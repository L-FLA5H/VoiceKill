package com.example.voicekill.client;

import com.example.voicekill.speech.SpeechPacket.RoundSpan;
import java.util.*;
import java.util.function.Supplier;

/** Attach round IDs on the input-audio timeline, before slow sentence review starts. */
public final class RoundTrackingDecoder implements CaptureSession.Decoder {
    public record Context(String session,String round,boolean running) {
        public static Context empty(){return new Context("","",false);}
    }
    private record Mark(long sample,Context context) {}
    private final CaptureSession.Decoder base;
    private final Supplier<Context> contexts;
    private final ArrayDeque<Mark> timeline=new ArrayDeque<>();
    private Context previous;
    private long samples,epoch,energyStart=-1,energyEnd;
    private RoundSpan rounds;

    public RoundTrackingDecoder(CaptureSession.Decoder base,Supplier<Context> contexts){this.base=base;this.contexts=contexts;previous=contexts.get();}
    @Override public List<String> accept(byte[] pcm) throws Exception {
        Context current=contexts.get();rounds=null;
        if(!current.session().equals(previous.session())||current.running()!=previous.running())discard();
        previous=current;
        if(timeline.isEmpty()||!timeline.getLast().context().equals(current))timeline.addLast(new Mark(samples,current));
        while(timeline.size()>64||(timeline.size()>1&&samples-timeline.stream().skip(1).findFirst().orElseThrow().sample()>32*16000L))timeline.removeFirst();
        long start=samples;samples+=pcm.length/2;
        double energy=0;for(float sample:AudioCapture.pcmSamples(pcm))energy+=(double)sample*sample;
        if(energy/Math.max(1,pcm.length/2)>1e-5){if(energyStart<0)energyStart=start;energyEnd=samples;}
        var result=base.accept(pcm);
        if(!result.isEmpty()) {
            var range=base.utteranceRange();
            long from=range==null?(energyStart<0?start:energyStart):range.start();
            long until=range==null?(energyStart<0?samples:energyEnd):range.end();
            Context first=at(from),last=at(Math.max(from,until-1));
            if(!current.session().isEmpty()) {
                if(first==null||last==null||!first.running()||!last.running()||!first.session().equals(last.session()))result=List.of();
                else rounds=new RoundSpan(first.round(),last.round());
            }
            energyStart=-1;
        } else if(base.boundary()==CaptureSession.Boundary.IDLE||base.boundary()==CaptureSession.Boundary.DISCARD)energyStart=-1;
        return result;
    }
    private Context at(long sample){Context found=null;for(var mark:timeline){if(mark.sample()>sample)break;found=mark.context();}return found;}
    @Override public void discard() throws Exception {base.discard();timeline.clear();samples=0;energyStart=-1;rounds=null;epoch++;}
    @Override public long epoch(){return epoch;}
    @Override public com.example.voicekill.speech.RecognitionHealth.Issue issue(){return base.issue();}
    @Override public RoundSpan rounds(){return rounds;}
    @Override public CaptureSession.Boundary boundary(){return base.boundary();}
    @Override public float[] utteranceSamples(){return base.utteranceSamples();}
    @Override public String partial(){return base.partial();}
    @Override public String displayFinal(List<String> candidates){return base.displayFinal(candidates);}
    @Override public void close(){base.close();}
}
