package io.noeriva.control;

import io.noeriva.control.devices.DeviceProtocol;
import io.noeriva.control.devices.TargetPolicy;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;
import reactor.core.scheduler.*;
import static io.noeriva.control.WorkbenchModels.*;
import static io.noeriva.control.SecurityConfiguration.Operator;

/** Bounded read-only network checks. Resolution is validated once and sockets use that exact address. */
@Service
public class CheckExecution {
    record Endpoint(String host,int port,String path,boolean tls) {}
    record Observation(String status,Double latencyMs,String message) {}
    private final WorkbenchService workbench;
    private final TargetPolicy policy;
    private final DatabaseClient db;
    private final boolean worker;
    private final Scheduler executor=Schedulers.newBoundedElastic(4,8,"synthetic-check");
    private final Semaphore permits=new Semaphore(4);
    private final AtomicBoolean scheduled=new AtomicBoolean();
    private final ConcurrentMap<String,String> memoryLeases=new ConcurrentHashMap<>();
    private final Set<SocketScope> active=ConcurrentHashMap.newKeySet();
    private static final int IO_TIMEOUT=3000;
    public CheckExecution(WorkbenchService workbench,TargetPolicy policy,ObjectProvider<DatabaseClient> database,Environment env) {
        this.workbench=workbench;this.policy=policy;this.db=database.getIfAvailable();
        worker=env.getProperty("NOERIVA_DEVICE_COLLECTOR_ENABLED",Boolean.class,false);
    }
    public Mono<CheckResult> run(Operator actor,String id,long revision) {
        if(actor.roles().stream().noneMatch(Set.of("ADMIN","OPERATOR")::contains))
            return Mono.error(new ApiException(HttpStatus.FORBIDDEN,"CHECK_EXECUTION_FORBIDDEN","An operator must run a network check"));
        return workbench.check(actor,id).flatMap(check->{
            if(check.archived()||check.revision()!=revision)return Mono.error(ApiException.conflict());
            if(!"MANUAL".equals(check.provenance()))return Mono.error(new ApiException(HttpStatus.CONFLICT,"SYNTHETIC_DEFINITION","Synthetic fixtures cannot execute real network checks"));
            return Mono.defer(()->{
                if(!permits.tryAcquire())return Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS,"CHECK_BUSY","Network check capacity reached"));
                String token=UUID.randomUUID().toString();
                return Mono.usingWhen(claim(actor.organizationId(),check,token).thenReturn(token), ignored ->
                    workbench.check(actor,id).flatMap(current->{
                        if(current.archived()||current.revision()!=revision)return Mono.error(ApiException.conflict());
                        return observe(current).flatMap(observation->workbench.reportResult(
                            new Operator(actor.username(),"",actor.organizationId(),List.of("COLLECTOR")),
                            new CheckResultInput(UUID.randomUUID().toString(),id,Instant.now(),observation.status(),observation.latencyMs(),observation.message(),"noeriva-native-"+current.type().toLowerCase(Locale.ROOT),current.provenance(),current.revision())));
                    }),
                    ignored->release(actor.organizationId(),check,token),
                    (ignored,error)->release(actor.organizationId(),check,token),
                    ignored->release(actor.organizationId(),check,token)
                ).doFinally(signal->permits.release());
            });
        });
    }
    Mono<Observation> observe(Check check) {
        return Mono.defer(()->{
            long start=System.nanoTime();
            SocketScope scope=new SocketScope();active.add(scope);
            return Mono.fromCallable(()->endpoint(check.type(),check.target()))
                .flatMap(endpoint->policy.resolve(endpoint.host()).flatMap(address->{
                    if(check.type().equals("DNS"))return Mono.just(new Observation("PASS",elapsed(start),"DNS 解析成功 · "+address+" · 系统解析器，全部结果在允许网段内"));
                    return Mono.fromCallable(()->probe(check.type(),endpoint,address,start,scope)).subscribeOn(executor);
                })).timeout(Duration.ofSeconds(10))
                .onErrorResume(error->Mono.just(failure(error,start)))
                .doFinally(signal->{scope.close();active.remove(scope);});
        });
    }
    static Endpoint endpoint(String type,String target) {
        if(target==null||target.isBlank()||target.length()>253||target.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)))throw new IllegalArgumentException("Invalid check target");
        if(type.equals("DNS")) {
            if(target.contains(":")||target.contains("/")||target.contains("@")||target.matches("[0-9.]+"))throw new IllegalArgumentException("DNS checks require a hostname");
            return new Endpoint(target,0,"",false);
        }
        if(!Set.of("TCP","TLS","HTTP","HTTPS").contains(type))throw new IllegalArgumentException("Unsupported check type");
        boolean http=type.equals("HTTP")||type.equals("HTTPS"),tls=type.equals("TLS")||type.equals("HTTPS");
        String scheme=type.toLowerCase(Locale.ROOT);
        URI uri;
        try {uri=URI.create(target.contains("://")?target:scheme+"://"+target);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid check target");}
        if(!scheme.equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||!http&&((uri.getRawPath()!=null&&!uri.getRawPath().isEmpty())||uri.getRawQuery()!=null))throw new IllegalArgumentException("Target scheme, credentials or path are invalid");
        String host=uri.getHost();if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        int port=uri.getPort();if(port==-1){if(type.equals("TCP"))throw new IllegalArgumentException("TCP target requires a port");port=tls?443:80;}
        if(port<1||port>65535)throw new IllegalArgumentException("Invalid target port");
        String path=uri.getRawPath()==null||uri.getRawPath().isEmpty()?"/":uri.getRawPath();if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
        return new Endpoint(host,port,path,tls);
    }
    static Observation probe(String type,Endpoint endpoint,String address,long start,SocketScope scope) throws Exception {
        Socket socket=new Socket();scope.attach(socket);socket.connect(new InetSocketAddress(InetAddress.ofLiteral(address),endpoint.port()),IO_TIMEOUT);socket.setSoTimeout(IO_TIMEOUT);
        if(endpoint.tls()) {
            SSLSocket ssl=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(socket,endpoint.host(),endpoint.port(),true);
            scope.attach(ssl);socket=ssl;ssl.setSoTimeout(IO_TIMEOUT);
            SSLParameters parameters=ssl.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");ssl.setSSLParameters(parameters);ssl.startHandshake();
            if(type.equals("TLS"))return new Observation("PASS",elapsed(start),"TLS 握手成功 · 系统信任与目标身份验证通过 · "+ssl.getSession().getProtocol());
        }
        if(type.equals("TCP"))return new Observation("PASS",elapsed(start),"TCP 连接成功 · "+address+":"+endpoint.port());
        String host=endpoint.host().contains(":")?"["+endpoint.host()+"]":endpoint.host();
        String request="GET "+endpoint.path()+" HTTP/1.1\r\nHost: "+host+":"+endpoint.port()+"\r\nConnection: close\r\nUser-Agent: NOERIVA-Check/1\r\nAccept: */*\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
        InputStream input=socket.getInputStream();ByteArrayOutputStream line=new ByteArrayOutputStream();
        while(line.size()<4096) {int value=input.read();if(value<0||value=='\n')break;line.write(value);if(elapsed(start)>9500)throw new SocketTimeoutException();}
        String status=line.toString(StandardCharsets.US_ASCII).trim();
        if(!status.matches("HTTP/1\\.[01] [1-5][0-9]{2}(?: .*)?"))return new Observation("FAIL",elapsed(start),"HTTP 响应状态行无效或超过 4096 字节");
        int code=Integer.parseInt(status.substring(9,12));
        return new Observation(code>=200&&code<400?"PASS":"FAIL",elapsed(start),"HTTP "+code+(code>=300&&code<400?" · 重定向未跟随":" · 已接收响应状态"));
    }
    static double elapsed(long start){return (System.nanoTime()-start)/1_000_000d;}
    static Observation failure(Throwable error,long start) {
        String code;String status="FAIL";
        if(error instanceof DeviceProtocol.Failure f){code=f.code();if(Set.of("TARGET_NOT_ALLOWED","INVALID_TARGET").contains(code))status="UNKNOWN";}
        else if(error instanceof IllegalArgumentException){code="INVALID_TARGET";status="UNKNOWN";}
        else if(error instanceof SSLException)code="TLS_VERIFICATION_OR_HANDSHAKE_FAILED";
        else if(error instanceof SocketTimeoutException||error instanceof TimeoutException)code="CHECK_TIMEOUT";
        else if(error instanceof ConnectException)code="CONNECTION_REFUSED_OR_UNREACHABLE";
        else code="NETWORK_CHECK_FAILED";
        return new Observation(status,elapsed(start),code);
    }
    static final class SocketScope implements AutoCloseable {
        private Socket socket;private boolean closed;
        synchronized void attach(Socket value)throws IOException{if(closed){value.close();throw new IOException("Check cancelled");}socket=value;}
        @Override public synchronized void close(){closed=true;if(socket!=null)try{socket.close();}catch(IOException ignored){}}
    }
    private Mono<Void> claim(String org,Check check,String token) {
        if(db==null)return Mono.defer(()->memoryLeases.putIfAbsent(org+"/"+check.id(),token)==null?Mono.empty():Mono.error(ApiException.conflict()));
        return db.sql("INSERT IGNORE INTO check_execution(organization_id,check_id) VALUES(:org,:id)").bind("org",org).bind("id",check.id()).fetch().rowsUpdated()
            .then(db.sql("UPDATE check_execution SET lease_id=:token,lease_until=UTC_TIMESTAMP(6)+INTERVAL 30 SECOND,last_attempt_at=UTC_TIMESTAMP(6),definition_revision=:revision WHERE organization_id=:org AND check_id=:id AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))")
                .bind("token",token).bind("revision",check.revision()).bind("org",org).bind("id",check.id()).fetch().rowsUpdated())
            .flatMap(count->count==1?Mono.empty():Mono.error(new ApiException(HttpStatus.CONFLICT,"CHECK_ALREADY_RUNNING","This check is already running")));
    }
    private Mono<Void> release(String org,Check check,String token) {
        if(db==null)return Mono.fromRunnable(()->memoryLeases.remove(org+"/"+check.id(),token));
        return db.sql("UPDATE check_execution SET lease_id=NULL,lease_until=NULL,next_run_at=:next WHERE organization_id=:org AND check_id=:id AND lease_id=:token")
            .bind("next",LocalDateTime.ofInstant(Instant.now().plusSeconds(check.intervalSeconds()),ZoneOffset.UTC)).bind("org",org).bind("id",check.id()).bind("token",token).fetch().rowsUpdated().then();
    }
    @Scheduled(fixedDelay=5000,initialDelay=20000) public void tick() {
        if(!worker||db==null||!scheduled.compareAndSet(false,true))return;
        record Due(String org,String id,long revision){}
        db.sql("SELECT r.organization_id,r.id,r.revision FROM workbench_record r LEFT JOIN check_execution e ON e.organization_id=r.organization_id AND e.check_id=r.id WHERE r.category='CHECK' AND r.status='ENABLED' AND JSON_UNQUOTE(r.payload->'$.provenance')='MANUAL' AND (e.next_run_at IS NULL OR e.next_run_at<=UTC_TIMESTAMP(6) OR e.definition_revision<>r.revision) AND (e.lease_until IS NULL OR e.lease_until<UTC_TIMESTAMP(6)) ORDER BY e.next_run_at,r.organization_id,r.id LIMIT 32")
            .map((row,metadata)->new Due(row.get("organization_id",String.class),row.get("id",String.class),row.get("revision",Long.class))).all()
            .flatMap(due->run(new Operator("check-worker","",due.org(),List.of("ADMIN")),due.id(),due.revision()).onErrorResume(error->Mono.empty()),4)
            .then().doFinally(signal->scheduled.set(false)).onErrorComplete().subscribe();
    }
    @PreDestroy public void close(){active.forEach(SocketScope::close);executor.dispose();}
}
