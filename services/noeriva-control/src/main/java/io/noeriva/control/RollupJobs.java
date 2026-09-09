package io.noeriva.control;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.noeriva.control.SecurityConfiguration.Operator;
import io.noeriva.query.*;
import io.r2dbc.spi.Row;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.*;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;

/** Durable bounded metric-repair jobs. This API does not perform exports or unrestricted analytical queries. */
@RestController
@RequestMapping("/api/v1/query-jobs")
public class RollupJobs {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RollupJobs.class);
    private static final String COLUMNS="id,organization_id,created_by,device_id,interface_id,source_id,direction,window_from,window_to,status,attempts,bucket_count,error_code,created_at,updated_at";
    private final DatabaseClient db;
    private final TransactionalOperator transaction;
    private final RollupWorker worker;
    private final String organization;
    private final ControlRepository repository;
    private final boolean enabled;
    private final Clock clock;
    private final AtomicBoolean running=new AtomicBoolean();
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final Disposable.Swap active=Disposables.swap();
    @Autowired
    public RollupJobs(ObjectProvider<DatabaseClient> database,ObjectProvider<TransactionalOperator> transaction,
            ObjectProvider<RollupWorker> worker,Environment env,ControlRepository repository) {
        this(database.getIfAvailable(),transaction.getIfAvailable(),worker.getIfAvailable(),
            env.getProperty("NOERIVA_ORGANIZATION_ID","default"),env.getProperty("NOERIVA_ROLLUP_SOURCE_ID","primary"),
            env.getProperty("noeriva.rollup.enabled",Boolean.class,env.getProperty("NOERIVA_ROLLUP_ENABLED",Boolean.class,false)),Clock.systemUTC(),repository);
    }
    RollupJobs(DatabaseClient db,TransactionalOperator transaction,RollupWorker worker,String organization,String source,boolean enabled,Clock clock) {
        this(db,transaction,worker,organization,source,enabled,clock,null);
    }
    RollupJobs(DatabaseClient db,TransactionalOperator transaction,RollupWorker worker,String organization,String legacySource,boolean enabled,Clock clock,ControlRepository repository) {
        this.db=db;this.transaction=transaction;this.worker=worker;this.organization=organization;this.enabled=enabled;this.clock=clock;this.repository=repository;
    }
    public record Request(@NotBlank @Size(max=64) String deviceId,@NotBlank @Size(max=64) String interfaceId,
                          @NotNull Instant from,@NotNull Instant to,@NotBlank String direction) {}
    public record Job(String id,String type,String organizationId,String createdBy,String deviceId,String interfaceId,String sourceId,
                      String direction,Instant from,Instant to,String status,int attempts,Integer bucketCount,String errorCode,Instant createdAt,Instant updatedAt) {}
    record Lease(Job job,String token) {}

    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Job> create(@AuthenticationPrincipal Operator actor,@Valid @RequestBody Request request) {
        return Mono.defer(()->{
            requireAdmin(actor);available();validate(request,clock.instant());
            String id=UUID.randomUUID().toString();
            // Serialize admission for one organization, preventing concurrent callers from exceeding 100 active jobs.
            return db.sql("SELECT id FROM organization WHERE id=:org FOR UPDATE").bind("org",actor.organizationId()).map((r,m)->r.get("id",String.class)).one()
                .switchIfEmpty(Mono.error(ApiException.missing()))
                .then(db.sql("SELECT COUNT(*) AS n FROM rollup_job WHERE organization_id=:org AND status IN ('PENDING','RUNNING')")
                    .bind("org",actor.organizationId()).map((r,m)->r.get("n",Long.class)).one())
                .flatMap(count->{
                    if(count>=100)return Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS,"JOB_QUEUE_CAPACITY","The organization has reached its active repair-job limit"));
                    return db.sql("SELECT i.source_id FROM network_interface i JOIN device d ON d.organization_id=i.organization_id AND d.id=i.device_id WHERE i.organization_id=:org AND i.device_id=:device AND i.id=:interface")
                        .bind("org",actor.organizationId()).bind("device",request.deviceId()).bind("interface",request.interfaceId())
                        .map((r,m)->r.get("source_id",String.class)).one().switchIfEmpty(Mono.error(ApiException.missing()));
                })
                // Keep the old constructor for embedded callers; its source comes from the same scoped directory row.
                .flatMap(scopedSource->repository==null?Mono.just(scopedSource):repository.interfaceSource(actor.organizationId(),request.deviceId(),request.interfaceId()).switchIfEmpty(Mono.error(ApiException.missing())))
                .flatMap(interfaceSource->{
                    if(!validId(interfaceSource))return Mono.error(new IllegalArgumentException("Invalid interface source identifier"));
                    return db.sql("INSERT INTO rollup_job(id,organization_id,created_by,device_id,interface_id,source_id,direction,window_from,window_to,status) VALUES(:id,:org,:actor,:device,:interface,:source,:direction,:from,:to,'PENDING')")
                    .bind("id",id).bind("org",actor.organizationId()).bind("actor",actor.username()).bind("device",request.deviceId())
                    .bind("interface",request.interfaceId()).bind("source",interfaceSource).bind("direction",request.direction())
                    .bind("from",local(request.from())).bind("to",local(request.to())).fetch().rowsUpdated();})
                .then(db.sql("INSERT INTO control_audit(id,organization_id,actor,action,resource_id) VALUES(:audit,:org,:actor,'ROLLUP_REPAIR_CREATED',:resource)")
                    .bind("audit",UUID.randomUUID().toString()).bind("org",actor.organizationId()).bind("actor",actor.username()).bind("resource",id).fetch().rowsUpdated())
                .as(transaction::transactional).then(find(actor.organizationId(),id));
        });
    }

    @GetMapping("/{id}")
    public Mono<Job> get(@AuthenticationPrincipal Operator actor,@PathVariable String id) {
        return Mono.defer(()->{available();if(!validId(id))throw new IllegalArgumentException("Invalid job identifier");return find(actor.organizationId(),id);});
    }
    private Mono<Job> find(String org,String id) {
        return db.sql("SELECT "+COLUMNS+" FROM rollup_job WHERE organization_id=:org AND id=:id")
            .bind("org",org).bind("id",id).map((r,m)->job(r)).one().switchIfEmpty(Mono.error(ApiException.missing()));
    }

    @Scheduled(fixedDelay=5000,initialDelay=15000)
    public void tick() {
        if(!enabled||worker==null||stopped.get()||!running.compareAndSet(false,true))return;
        active.update(runOne().doFinally(signal->running.set(false)).subscribe(value->{},error->
            LOG.warn("rollup_job_poll_failed type={}",error.getClass().getSimpleName())));
    }
    Mono<Void> runOne() {
        return claim().flatMap(lease->worker.recompute(lease.job.organizationId(),lease.job.deviceId(),lease.job.interfaceId(),lease.job.sourceId(),
                lease.job.from(),lease.job.to(),lease.job.direction()).timeout(Duration.ofSeconds(30))
            .flatMap(count->finish(lease,"SUCCEEDED",count,null))
            .onErrorResume(error->finish(lease,"FAILED",null,errorCode(error)))).then();
    }
    Mono<Lease> claim() {
        String token=UUID.randomUUID().toString();
        return db.sql("UPDATE rollup_job SET status='FAILED',error_code='LEASE_EXHAUSTED',lease_owner=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=:org AND status='RUNNING' AND lease_until<UTC_TIMESTAMP(6) AND attempts>=3")
            .bind("org",organization).fetch().rowsUpdated()
            .then(db.sql("SELECT "+COLUMNS+" FROM rollup_job WHERE organization_id=:org AND (status='PENDING' OR (status='RUNNING' AND lease_until<UTC_TIMESTAMP(6) AND attempts<3)) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
                .bind("org",organization).map((r,m)->job(r)).one())
            .flatMap(job->db.sql("UPDATE rollup_job SET status='RUNNING',attempts=attempts+1,lease_owner=:owner,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 MINUTE),updated_at=UTC_TIMESTAMP(6),error_code=NULL WHERE organization_id=:org AND id=:id AND (status='PENDING' OR (status='RUNNING' AND lease_until<UTC_TIMESTAMP(6) AND attempts<3))")
                .bind("owner",token).bind("org",organization).bind("id",job.id()).fetch().rowsUpdated()
                .flatMap(changed->changed==1?Mono.just(new Lease(job,token)):Mono.empty()))
            .as(transaction::transactional);
    }
    Mono<Void> finish(Lease lease,String status,Integer count,String error) {
        var update=db.sql("UPDATE rollup_job SET status=:status,bucket_count=:count,error_code=:error,lease_owner=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=:org AND id=:id AND status='RUNNING' AND lease_owner=:owner")
            .bind("status",status).bind("org",lease.job.organizationId()).bind("id",lease.job.id()).bind("owner",lease.token);
        update=count==null?update.bindNull("count",Integer.class):update.bind("count",count);
        update=error==null?update.bindNull("error",String.class):update.bind("error",error);
        return update.fetch().rowsUpdated().then();
    }

    static void validate(Request request,Instant now) {
        if(request==null||!validId(request.deviceId())||!validId(request.interfaceId())||request.from()==null||request.to()==null
                ||!request.from().isBefore(request.to())||Duration.between(request.from(),request.to()).compareTo(Duration.ofDays(1))>0
                ||request.to().isAfter(now)||request.from().getNano()!=0||request.to().getNano()!=0
                ||Math.floorMod(request.from().getEpochSecond(),300)!=0||Math.floorMod(request.to().getEpochSecond(),300)!=0
                ||!Set.of("rx","tx").contains(request.direction()))
            throw new IllegalArgumentException("Repair jobs require one device/interface, rx or tx, and a closed UTC-five-minute-aligned window of at most one day");
    }
    static void requireAdmin(Operator actor) {
        if(actor==null||!actor.roles().contains("ADMIN"))throw new ApiException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","A platform administrator must start a historical repair job");
    }
    private void available() {
        if(db==null||transaction==null||worker==null)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"REPAIR_JOBS_UNAVAILABLE","Historical repair jobs require the connected data profile");
    }
    private static boolean validId(String id) {return id!=null&&id.matches("[A-Za-z0-9_.:@-]{1,64}");}
    private static String errorCode(Throwable error) {
        if(error instanceof java.util.concurrent.TimeoutException)return "DEADLINE_EXCEEDED";
        if(error instanceof QueryRejectedException)return "QUERY_CAPACITY";
        if(error instanceof ProviderUnavailableException p)return p.getMessage().contains("RECOMPUTATION_UNAVAILABLE")?"RECOMPUTATION_UNAVAILABLE":"PROVIDER_UNAVAILABLE";
        if(error instanceof IllegalArgumentException)return "INVALID_SOURCE_DATA";
        return "REPAIR_FAILED";
    }
    private static Job job(Row row) {
        return new Job(row.get("id",String.class),"ROLLUP_REPAIR",row.get("organization_id",String.class),row.get("created_by",String.class),
            row.get("device_id",String.class),row.get("interface_id",String.class),row.get("source_id",String.class),row.get("direction",String.class),
            instant(row,"window_from"),instant(row,"window_to"),row.get("status",String.class),row.get("attempts",Integer.class),
            row.get("bucket_count",Integer.class),row.get("error_code",String.class),instant(row,"created_at"),instant(row,"updated_at"));
    }
    private static Instant instant(Row row,String field) {var value=row.get(field,LocalDateTime.class);return value==null?null:value.toInstant(ZoneOffset.UTC);}
    private static LocalDateTime local(Instant value) {return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    @PreDestroy public void close() {stopped.set(true);active.dispose();}
}
