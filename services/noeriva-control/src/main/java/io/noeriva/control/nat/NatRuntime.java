package io.noeriva.control.nat;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.nat.NatModels.*;
import static io.noeriva.control.nat.NatSourceStore.Delta;

@Configuration @Profile("production")
public class NatRuntime implements SmartLifecycle {
 private final NatSourceStore sources;private final NatHistory history;private final KafkaTemplate<String,String> kafka;private final JsonMapper json;private final boolean enabled;private final int port;
 private volatile NatUdpEngine engine;private volatile Map<String,Binding> bindings=Map.of();private volatile Instant refreshed=Instant.EPOCH;private final AtomicBoolean refreshing=new AtomicBoolean();
 private volatile ScheduledExecutorService maintenance;
 private record Pending(Binding binding,Delta delta){}private final Map<String,Pending> pending=new HashMap<>();
 public NatRuntime(NatSourceStore sources,NatHistory history,KafkaTemplate<String,String> kafka,JsonMapper json,Environment env){this.sources=sources;this.history=history;this.kafka=kafka;this.json=json;enabled=env.getProperty("NOERIVA_NAT_RECEIVER_ENABLED",Boolean.class,false);port=env.getProperty("NOERIVA_NAT_UDP_PORT",Integer.class,2055);if(port<1||port>65535)throw new IllegalArgumentException("NAT UDP port outside range");}
 // Listener post-processing needs the factory while this runtime bean is still
 // being initialized; a static factory avoids requiring that unfinished bean.
 @Bean static ConcurrentKafkaListenerContainerFactory<String,String> natKafkaFactory(ConsumerFactory<String,String> consumers){var f=new ConcurrentKafkaListenerContainerFactory<String,String>();f.setConsumerFactory(consumers);f.setConcurrency(1);f.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(2000,Long.MAX_VALUE)));return f;}
 @KafkaListener(topics="noeriva.nat.v1",groupId="noeriva-nat-sink-v1",containerFactory="natKafkaFactory")
 public void persist(String value){try{Envelope batch=json.readValue(value,Envelope.class);sources.requireDevice(batch.organizationId(),batch.deviceId()).then(history.append(batch)).then(sources.persisted(batch.organizationId(),batch.deviceId(),batch.events().size())).block(Duration.ofSeconds(20));}catch(Exception e){throw new IllegalStateException("NAT_SINK_PENDING_RETRY");}}
 @Override public synchronized void start(){if(!enabled||engine!=null)return;try{engine=new NatUdpEngine(port,()->refreshed.plusSeconds(10).isAfter(Instant.now())?bindings:Map.of(),batch->{try{kafka.send("noeriva.nat.v1",batch.organizationId()+"/"+batch.deviceId(),json.writeValueAsString(batch)).get(35,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException("NAT_KAFKA_ACK_FAILED");}},this::accumulate);maintenance=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"nat-source-maintenance");t.setDaemon(true);return t;});maintenance.scheduleWithFixedDelay(this::refresh,0,3,TimeUnit.SECONDS);}catch(java.net.SocketException e){throw new IllegalStateException("NAT_UDP_BIND_FAILED");}}
 @Override public void stop(){NatUdpEngine current=engine;engine=null;ScheduledExecutorService executor=maintenance;maintenance=null;if(executor!=null)executor.shutdownNow();if(current!=null)current.close();flush();}
 @Override public boolean isRunning(){return engine!=null;}@Override public boolean isAutoStartup(){return enabled;}
 public void refresh(){if(!enabled||!refreshing.compareAndSet(false,true))return;try{List<Binding> rows=sources.bindings().block(Duration.ofSeconds(3));var next=new HashMap<String,Binding>();for(Binding b:rows)next.put(b.sourceAddress(),b);bindings=Map.copyOf(next);refreshed=Instant.now();}catch(Exception e){bindings=Map.of();refreshed=Instant.EPOCH;}finally{refreshing.set(false);}flush();}
 private synchronized void accumulate(Binding b,Delta d){String key=b.organizationId()+"/"+b.deviceId()+"/"+b.sourceAddress();if(!pending.containsKey(key)&&pending.size()>=512)return;Pending old=pending.get(key);pending.put(key,new Pending(b,old==null?d:plus(old.delta,d)));}
 private static Delta plus(Delta a,Delta b){var flags=new LinkedHashSet<String>(BASE_FLAGS);flags.addAll(a.flags());flags.addAll(b.flags());return new Delta(a.templates()+b.templates(),a.received()+b.received(),a.accepted()+b.accepted(),a.dropped()+b.dropped(),a.sequenceGaps()+b.sequenceGaps(),a.unknownTemplates()+b.unknownTemplates(),a.parseErrors()+b.parseErrors(),a.duplicatePackets()+b.duplicatePackets(),a.restartCount()+b.restartCount(),latest(a.lastPacketAt(),b.lastPacketAt()),latest(a.lastEventAt(),b.lastEventAt()),b.error()==null?a.error():b.error(),List.copyOf(flags));}
 private static Instant latest(Instant a,Instant b){return a==null?b:b==null||a.isAfter(b)?a:b;}
 private void flush(){Map<String,Pending> copy;synchronized(this){copy=new java.util.concurrent.ConcurrentHashMap<>(pending);pending.clear();}try{reactor.core.publisher.Flux.fromIterable(List.copyOf(copy.values())).flatMap(p->sources.record(p.binding,p.delta).timeout(Duration.ofSeconds(2)).doOnSuccess(v->copy.remove(p.binding.organizationId()+"/"+p.binding.deviceId()+"/"+p.binding.sourceAddress())),4).then().block(Duration.ofSeconds(4));}catch(Exception ignored){}for(Pending p:copy.values())accumulate(p.binding,p.delta);}
}
