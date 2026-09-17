package io.noeriva.control.nat;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.*;
import static io.noeriva.control.nat.NatModels.*;
import static io.noeriva.control.nat.NatSourceStore.Delta;

/** Two dedicated threads, 128 datagrams / at most 8 MiB queued; no network work on WebFlux loops. */
public final class NatUdpEngine implements AutoCloseable {
 private final DatagramSocket socket;private final Supplier<Map<String,Binding>> bindings;private final Consumer<Envelope> publish;private final BiConsumer<Binding,Delta> stats;
 private final ArrayBlockingQueue<Packet> queue=new ArrayBlockingQueue<>(128);private final NatDecoder decoder=new NatDecoder();private final LongAdder rejected=new LongAdder();
 private final Thread reader,worker;private volatile boolean running=true;
 private record Packet(Binding binding,byte[] payload,Instant at){}
 public NatUdpEngine(int port,Supplier<Map<String,Binding>> bindings,Consumer<Envelope> publish,BiConsumer<Binding,Delta> stats)throws SocketException{
  this.bindings=bindings;this.publish=publish;this.stats=stats;socket=new DatagramSocket(null);socket.setReuseAddress(false);socket.setReceiveBufferSize(4*1024*1024);socket.bind(new InetSocketAddress("0.0.0.0",port));
  reader=new Thread(this::receive,"nat-udp-receiver");worker=new Thread(this::process,"nat-udp-publisher");reader.setDaemon(true);worker.setDaemon(true);reader.start();worker.start();
 }
 public int port(){return socket.getLocalPort();}public long rejectedSources(){return rejected.sum();}
 private void receive(){byte[] buffer=new byte[65508];while(running){try{var datagram=new DatagramPacket(buffer,buffer.length);socket.receive(datagram);String address=datagram.getAddress().getHostAddress();Binding source=bindings.get().get(address);if(source==null){rejected.increment();continue;}Instant at=Instant.now();if(datagram.getLength()>65507||!queue.offer(new Packet(source,Arrays.copyOf(buffer,datagram.getLength()),at)))stats.accept(source,new Delta(0,1,0,1,0,0,0,0,0,at,null,"QUEUE_DROPPED",List.of("QUEUE_DROPPED")));}catch(SocketException e){if(running)rejected.increment();}catch(Exception e){rejected.increment();}}}
 private void process(){while(running||!queue.isEmpty()){Packet p;try{p=queue.poll(250,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}if(p==null)continue;Decoded decoded;
  try{decoded=decoder.decode(p.binding.organizationId(),p.binding.deviceId(),p.binding.siteId(),p.binding.sourceAddress(),p.payload,p.at);}catch(IllegalArgumentException e){stats.accept(p.binding,new Delta(0,1,0,1,0,0,1,0,0,p.at,null,"PARSE_ERROR",List.of("PARSE_ERROR")));continue;}
  long accepted=0;String error=null;try{for(int offset=0;offset<decoded.events().size();offset+=128){List<NatEvent> batch=List.copyOf(decoded.events().subList(offset,Math.min(offset+128,decoded.events().size())));publish.accept(new Envelope(p.binding.organizationId(),p.binding.deviceId(),batch));accepted+=batch.size();}decoder.confirm(p.binding.organizationId(),p.binding.deviceId(),p.binding.sourceAddress(),p.payload);}
  catch(Exception e){error="PUBLISH_FAILED";decoder.forget(p.binding.organizationId(),p.binding.deviceId(),p.binding.sourceAddress(),p.payload);}
  if(error==null&&decoded.unknownTemplates()>0)error="NO_TEMPLATE";if(error==null&&decoded.qualityFlags().contains("OUT_OF_ORDER_DATA_REJECTED"))error="OUT_OF_ORDER_DATA_REJECTED";if(error==null&&decoded.qualityFlags().contains("UNSUPPORTED_PORT_BLOCK"))error="UNSUPPORTED_PORT_BLOCK";if(error==null&&(decoded.sequenceGap()||decoded.restart()))error=decoded.restart()?"EXPORTER_RESTART":"SEQUENCE_GAP_OR_REORDER";
  var flags=new LinkedHashSet<>(decoded.qualityFlags());if(error!=null)flags.add(error);
  stats.accept(p.binding,new Delta(decoded.templates(),1,accepted,Set.of("PUBLISH_FAILED","OUT_OF_ORDER_DATA_REJECTED").contains(Objects.toString(error,""))?1:0,decoded.sequenceGap()?1:0,decoded.unknownTemplates(),0,decoded.duplicate()?1:0,decoded.restart()?1:0,p.at,accepted>0?p.at:null,error,List.copyOf(flags)));
 }}
 @Override public void close(){running=false;socket.close();reader.interrupt();worker.interrupt();try{reader.join(2000);worker.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}Packet p;while((p=queue.poll())!=null)stats.accept(p.binding,new Delta(0,0,0,1,0,0,0,0,0,p.at,null,"SHUTDOWN_DROPPED",List.of("SHUTDOWN_DROPPED")));}
}
