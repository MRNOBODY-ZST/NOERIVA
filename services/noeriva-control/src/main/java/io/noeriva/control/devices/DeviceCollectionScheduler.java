package io.noeriva.control.devices;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

@Component @Profile("production") @ConditionalOnProperty(name="NOERIVA_DEVICE_COLLECTOR_ENABLED",havingValue="true")
public class DeviceCollectionScheduler {
    private final DeviceAccessStore store;private final DeviceAccessService service;private final String org;
    private final AtomicBoolean running=new AtomicBoolean();private volatile reactor.core.Disposable active;private volatile java.time.Instant lastSuccess;
    public DeviceCollectionScheduler(DeviceAccessStore store,DeviceAccessService service,Environment env){this.store=store;this.service=service;this.org=env.getProperty("NOERIVA_ORGANIZATION_ID","default");}
    @Scheduled(fixedDelay=5000,initialDelay=15000) public void sweep(){
        if(!running.compareAndSet(false,true))return;
        active=store.heartbeat(org,lastSuccess).thenMany(store.due(org,32)).flatMap(v->service.poll(v,true,true).doOnNext(result->{if(!result.status().equals("ERROR"))lastSuccess=java.time.Instant.now();}).onErrorResume(e->Mono.empty()),4)
            .timeout(Duration.ofMinutes(9)).doFinally(signal->running.set(false))
            .subscribe(v->{},error->LoggerFactory.getLogger(DeviceCollectionScheduler.class).warn("device_poll_sweep_failed type={}",error.getClass().getSimpleName()));
    }
    @Scheduled(fixedDelay=20000,initialDelay=5000) public void heartbeat(){store.heartbeat(org,lastSuccess).timeout(Duration.ofSeconds(4)).subscribe(v->{},e->LoggerFactory.getLogger(DeviceCollectionScheduler.class).warn("device_heartbeat_failed type={}",e.getClass().getSimpleName()));}
    @jakarta.annotation.PreDestroy public void close(){var task=active;if(task!=null)task.dispose();}
}
