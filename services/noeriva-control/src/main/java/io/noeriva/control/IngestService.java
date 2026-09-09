package io.noeriva.control;

import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import io.noeriva.query.QueryAdmission;
import io.noeriva.query.QueryLane;
import java.time.*;
import static io.noeriva.control.Models.*;

@Service
public class IngestService {
    public record Envelope(String organizationId,Instant acceptedAt,Observation observation) {}
    private final ControlRepository repository;
    private final ObjectProvider<KafkaTemplate<String,String>> kafka;
    private final JsonMapper json;
    private final Scheduler producers=Schedulers.newBoundedElastic(4,64,"kafka-producer");
    private final QueryAdmission admission=new QueryAdmission(16,Duration.ofSeconds(40));
    public IngestService(ControlRepository repository,ObjectProvider<KafkaTemplate<String,String>> kafka,JsonMapper json){this.repository=repository;this.kafka=kafka;this.json=json;}
    public Mono<AcceptedBatch> accept(String org,IngestBatch batch){return admission.execute(QueryLane.INTERACTIVE_STATE,()->{
        var received=Instant.now();batch.events().forEach(o->ProjectionPolicy.accepts(null,o,received));
        // Validate the entire resource scope before publishing any record.
        return Flux.fromIterable(batch.events()).map(Observation::deviceId).distinct().flatMap(id->repository.device(org,id).switchIfEmpty(Mono.error(ApiException.missing())),8).then()
            .thenMany(Flux.fromIterable(batch.events()).flatMap(o->{
                if(kafka.getIfAvailable()==null)return repository.project(org,o).then();
                return Mono.fromCallable(()->kafka.getObject().send("noeriva.events.v1",org+"/"+o.deviceId(),json.writeValueAsString(new Envelope(org,received,o))))
                    .subscribeOn(producers).flatMap(f->Mono.fromFuture(f)).then();
            },8)).then(Mono.fromSupplier(()->new AcceptedBatch(batch.batchId(),kafka.getIfAvailable()==null?"SIMULATED":"DURABLY_QUEUED",batch.events().size(),received)));
    });}
}
