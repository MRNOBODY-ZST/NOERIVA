package io.noeriva.control.nat;

import java.net.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static io.noeriva.control.nat.NatModels.*;

class NatUdpTest {
 static final Binding SOURCE=new Binding("trusted-org","gateway","site","127.0.0.1",1);
 Engine engine(Supplier<Map<String,Binding>> bindings,Consumer<Envelope> sink,BiConsumer<Binding,NatSourceStore.Delta> stats){try{Object e=Class.forName("io.noeriva.control.nat.NatUdpEngine").getConstructor(int.class,Supplier.class,Consumer.class,BiConsumer.class).newInstance(0,bindings,sink,stats);return new Engine(e);}catch(Exception e){throw new AssertionError("NAT requires bounded real UDP transport",e);}}
 record Engine(Object value) implements AutoCloseable {int port(){return ((Number)invoke("port")).intValue();}long rejected(){return ((Number)invoke("rejectedSources")).longValue();}Object invoke(String method){try{return value.getClass().getMethod(method).invoke(value);}catch(Exception e){throw new AssertionError(e);}}public void close(){invoke("close");}}
 void send(int port,byte[] payload)throws Exception{try(var s=new DatagramSocket()){s.send(new DatagramPacket(payload,payload.length,InetAddress.getLoopbackAddress(),port));}}
 byte[] packet(int seq){return NatDecoderTest.packet(200,seq,1000+seq,NatDecoderTest.template(NatDecoderTest.FIELDS),NatDecoderTest.data(NatDecoderTest.FIELDS,6,1,0));}
 @Test void actualUdpPublishesOnlyTrustedSourceAndPreservesScope()throws Exception{var writes=new LinkedBlockingQueue<Envelope>();try(var e=engine(()->Map.of("127.0.0.1",SOURCE),writes::add,(b,d)->{})){send(e.port(),packet(1));var written=writes.poll(5,TimeUnit.SECONDS);assertThat(written).isNotNull();assertThat(written.organizationId()).isEqualTo("trusted-org");assertThat(written.events()).hasSize(1);assertThat(written.events().getFirst().sourceDomain()).isEqualTo(200);}}
 @Test void unknownExporterIsRejectedBeforeTemplateOrSinkAllocation()throws Exception{var writes=new AtomicInteger();try(var e=engine(Map::of,b->writes.incrementAndGet(),(b,d)->{})){send(e.port(),packet(1));await().atMost(Duration.ofSeconds(3)).until(()->e.rejected()>0);assertThat(writes).hasValue(0);}}
 @Test void publishFailureIsVisibleAndDoesNotBecomeAccepted()throws Exception{var deltas=new LinkedBlockingQueue<NatSourceStore.Delta>();try(var e=engine(()->Map.of("127.0.0.1",SOURCE),b->{throw new IllegalStateException("synthetic sink down");},(b,d)->deltas.add(d))){send(e.port(),packet(1));await().atMost(Duration.ofSeconds(5)).until(()->deltas.stream().anyMatch(d->"PUBLISH_FAILED".equals(d.error())));assertThat(deltas.stream().mapToLong(NatSourceStore.Delta::accepted).sum()).isZero();assertThat(deltas.stream().mapToLong(NatSourceStore.Delta::dropped).sum()).isPositive();}}
 @Test void boundedQueueReportsOverloadWithoutBlockingUdpReader()throws Exception{var latch=new CountDownLatch(1);var dropped=new AtomicLong();try(var e=engine(()->Map.of("127.0.0.1",SOURCE),b->{try{latch.await(5,TimeUnit.SECONDS);}catch(InterruptedException x){Thread.currentThread().interrupt();}},(b,d)->dropped.addAndGet(d.dropped()))){try(var socket=new DatagramSocket()){for(int i=0;i<800;i++){byte[] p=packet(i);socket.send(new DatagramPacket(p,p.length,InetAddress.getLoopbackAddress(),e.port()));}}await().atMost(Duration.ofSeconds(4)).until(()->dropped.get()>0);}finally{latch.countDown();}}
 @Test void closeReleasesUdpPort()throws Exception{var e=engine(Map::of,b->{},(b,d)->{});int port=e.port();e.close();try(var replacement=new DatagramSocket(port)){assertThat(replacement.isBound()).isTrue();}}
 @Test void identicalDatagramCanRetryAfterKafkaFailureWithoutNewTemplate()throws Exception{var writes=new LinkedBlockingQueue<Envelope>();var failures=new AtomicInteger();var attempts=new AtomicInteger();try(var e=engine(()->Map.of("127.0.0.1",SOURCE),b->{if(attempts.incrementAndGet()==1)throw new IllegalStateException("synthetic failure");writes.add(b);},(b,d)->{if("PUBLISH_FAILED".equals(d.error()))failures.incrementAndGet();})){byte[] p=packet(1);send(e.port(),p);await().atMost(Duration.ofSeconds(3)).until(()->failures.get()==1);send(e.port(),p);assertThat(writes.poll(3,TimeUnit.SECONDS)).isNotNull();assertThat(attempts).hasValue(2);}}
}
