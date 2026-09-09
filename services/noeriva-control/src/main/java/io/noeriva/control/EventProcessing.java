package io.noeriva.control;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component @Profile("production")
public class EventProcessing {
    private final MySqlRepository repository; private final HistoryStore history; private final JsonMapper json;
    private final DatabaseClient db; private final KafkaTemplate<String,String> kafka;
    private final AtomicBoolean publishing=new AtomicBoolean();
    public EventProcessing(MySqlRepository repository,HistoryStore history,JsonMapper json,DatabaseClient db,KafkaTemplate<String,String> kafka){this.repository=repository;this.history=history;this.json=json;this.db=db;this.kafka=kafka;}
    @KafkaListener(topics="noeriva.events.v1")
    public void project(String value){
        var envelope=json.readValue(value,IngestService.Envelope.class);
        // Dedicated Kafka consumer thread is an explicit blocking boundary. Offsets commit only after
        // synchronous ClickHouse acknowledgement and idempotent MySQL checkpoint completion.
        history.append(envelope.organizationId(),envelope.observation(),envelope.acceptedAt())
            .then(repository.project(envelope.organizationId(),envelope.observation()))
            .doOnError(error->org.slf4j.LoggerFactory.getLogger(EventProcessing.class).warn("projection_failed eventId={} type={} message={}",envelope.observation().id(),error.getClass().getSimpleName(),error.getMessage()))
            .block(Duration.ofSeconds(30));
    }
    @Scheduled(fixedDelay=1000)
    public void publishOutbox(){
        if(!publishing.compareAndSet(false,true))return;
        db.sql("SELECT id,organization_id,aggregate_id,kind,payload FROM outbox WHERE published_at IS NULL ORDER BY created_at,id LIMIT 100")
            .map((r,m)->Map.of("id",r.get("id",String.class),"organizationId",r.get("organization_id",String.class),"aggregateId",r.get("aggregate_id",String.class),"kind",r.get("kind",String.class),"payload",r.get("payload",String.class))).all()
            .flatMap(row->Mono.fromCallable(()->kafka.send("noeriva.control.v1",row.get("organizationId")+"/"+row.get("aggregateId"),json.writeValueAsString(row)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).flatMap(Mono::fromFuture)
                .then(db.sql("UPDATE outbox SET published_at=UTC_TIMESTAMP(6) WHERE id=:id AND published_at IS NULL").bind("id",row.get("id")).fetch().rowsUpdated()),4)
            .timeout(Duration.ofSeconds(35)).doFinally(s->publishing.set(false))
            .subscribe(v->{},e->org.slf4j.LoggerFactory.getLogger(getClass()).warn("outbox_retry_pending type={}",e.getClass().getSimpleName()));
    }
}
