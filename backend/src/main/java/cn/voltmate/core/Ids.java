package cn.voltmate.core;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Ids {
    private static final long EPOCH=1_704_067_200_000L;
    private final long workerId;
    private long lastMillisecond=-1,sequence=0;
    public Ids(@Value("${app.worker-id:1}") long workerId){
        if(workerId<0||workerId>1023)throw new IllegalArgumentException("APP_WORKER_ID must be 0..1023");
        this.workerId=workerId;
    }
    public synchronized long next() {
        long ms=Instant.now().toEpochMilli()-EPOCH;
        if(ms<lastMillisecond)throw new IllegalStateException("Clock moved backwards");
        if(ms==lastMillisecond){
            sequence=(sequence+1)&4095;
            if(sequence==0)do{ms=Instant.now().toEpochMilli()-EPOCH;}while(ms<=lastMillisecond);
        }else sequence=0;
        lastMillisecond=ms;
        return (ms<<22)|(workerId<<12)|sequence;
    }
}
