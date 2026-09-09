package io.noeriva.control;

import io.noeriva.query.RollupWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Flux;
import java.time.*;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/** Run one worker replica per assigned organization; API replicas leave this disabled. */
@Component @Profile("production") @ConditionalOnProperty(name="noeriva.rollup.enabled",havingValue="true")
public class RollupScheduler {
    private final DatabaseClient db;private final RollupWorker worker;private final String org,source;
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile reactor.core.Disposable active;
    private volatile String cursor="";
    public RollupScheduler(DatabaseClient db,RollupWorker worker,Environment env){this.db=db;this.worker=worker;this.org=env.getProperty("NOERIVA_ORGANIZATION_ID","default");this.source=env.getProperty("NOERIVA_ROLLUP_SOURCE_ID","primary");}
    @Scheduled(fixedDelay=60000,initialDelay=10000)
    public void sweep(){
        if(!running.compareAndSet(false,true))return;
        Instant end=Instant.ofEpochSecond(Math.floorDiv(Instant.now().minusSeconds(90).getEpochSecond(),300)*300);Instant from=end.minusSeconds(900);
        active=db.sql("SELECT id,device_id,source_id FROM network_interface WHERE organization_id=:org AND id>:cursor ORDER BY id LIMIT 32").bind("org",org).bind("cursor",cursor)
            .map((r,m)->new String[]{r.get("device_id",String.class),r.get("id",String.class),r.get("source_id",String.class)}).all()
            .collectList().flatMapMany(rows->{cursor=rows.size()<32?"":rows.getLast()[1];return Flux.fromIterable(rows);})
            .concatMap(ids->Flux.fromIterable(java.util.List.of("rx","tx")).concatMap(direction->worker.recompute(org,ids[0],ids[1],ids[2].equals("primary")?source:ids[2],from,end,direction)
                .onErrorResume(e->{LoggerFactory.getLogger(RollupScheduler.class).warn("rollup_window_incomplete device={} interface={} type={}",ids[0],ids[1],e.getClass().getSimpleName());return reactor.core.publisher.Mono.empty();})),1)
            .doFinally(signal->running.set(false)).subscribe(value->{},error->LoggerFactory.getLogger(RollupScheduler.class).warn("rollup_sweep_failed type={}",error.getClass().getSimpleName()));
    }
    @jakarta.annotation.PreDestroy public void close(){var task=active;if(task!=null)task.dispose();}
}
