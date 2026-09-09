package io.noeriva.control.applications;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import org.slf4j.LoggerFactory;
@Component @Profile("production") @ConditionalOnProperty(name="NOERIVA_APPLICATION_COLLECTOR_ENABLED",havingValue="true")
public class ApplicationScheduler {
    private final ApplicationStore store;private final ApplicationService service;private final String org;private final AtomicBoolean running=new AtomicBoolean();private volatile reactor.core.Disposable active;
    public ApplicationScheduler(ApplicationStore store,ApplicationService service,Environment env){this.store=store;this.service=service;this.org=env.getProperty("NOERIVA_ORGANIZATION_ID","default");}
    @Scheduled(fixedDelay=5000,initialDelay=20000) public void sweep(){if(!running.compareAndSet(false,true))return;active=store.due(org,32).flatMap(v->service.poll(v,true).onErrorResume(e->Mono.empty()),4).timeout(Duration.ofMinutes(7)).doFinally(signal->running.set(false)).subscribe(v->{},e->LoggerFactory.getLogger(ApplicationScheduler.class).warn("application_sweep_failed type={}",e.getClass().getSimpleName()));}
    @jakarta.annotation.PreDestroy public void close(){if(active!=null)active.dispose();}
}
