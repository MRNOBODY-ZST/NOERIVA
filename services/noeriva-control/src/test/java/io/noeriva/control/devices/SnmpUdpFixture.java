package io.noeriva.control.devices;

import org.snmp4j.*;
import org.snmp4j.mp.*;
import org.snmp4j.security.*;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic fixture using actual UDP and SNMPv2c/USM encoding. No hardware claim. */
public final class SnmpUdpFixture implements AutoCloseable {
    final NavigableMap<OID, Variable> values = new ConcurrentSkipListMap<>();
    final AtomicInteger requests = new AtomicInteger();
    private final Snmp agent;
    private final DefaultUdpTransportMapping transport;
    private final String community;
    volatile boolean wrongOrder;
    volatile boolean silent;
    volatile long firstResponseDelayMillis;
    final Set<OID> rejectedGets=java.util.concurrent.ConcurrentHashMap.newKeySet();
    volatile int rejectedGetStatus=PDU.genErr;
    public SnmpUdpFixture() throws Exception { this("127.0.0.1",0,"synthetic-community","synthetic-user","auth-test-pass","priv-test-pass"); }
    public SnmpUdpFixture(String bind, int port, String community, String username, String auth, String privacy) throws Exception {
        this.community=community;
        var security=new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
        security.addAuthenticationProtocol(new AuthSHA());
        security.addAuthenticationProtocol(new AuthMD5());
        security.addAuthenticationProtocol(new AuthHMAC192SHA256());
        security.addAuthenticationProtocol(new AuthHMAC384SHA512());
        security.addPrivacyProtocol(new PrivAES128());
        security.addPrivacyProtocol(new PrivDES());
        byte[] engine=OctetString.fromHexString("80:00:13:70:01:02:03:04:05:06:07:08").getValue();
        var counters=new CounterSupport();
        var usm=new USM(security,new OctetString(engine),7,counters);
        usm.addUser(new UsmUser(new OctetString(username),AuthHMAC192SHA256.ID,new OctetString(auth),PrivAES128.ID,new OctetString(privacy)));
        usm.addUser(new UsmUser(new OctetString("sha1-user"),AuthSHA.ID,new OctetString(auth),PrivDES.ID,new OctetString(privacy)));
        usm.addUser(new UsmUser(new OctetString("md5-user"),AuthMD5.ID,new OctetString(auth),null,null));
        usm.addUser(new UsmUser(new OctetString("sha512-user"),AuthHMAC384SHA512.ID,new OctetString(auth),PrivAES128.ID,new OctetString(privacy)));
        usm.addUser(new UsmUser(new OctetString("plain-user"),null,null,null,null));
        var dispatcher=new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv2c());
        dispatcher.addMessageProcessingModel(new MPv3(engine,null,security,SecurityModels.getCollection(new SecurityModel[]{usm}),counters));
        transport=new DefaultUdpTransportMapping(new UdpAddress(InetAddress.getByName(bind),port));
        agent=new Snmp(dispatcher,transport);
        agent.addCommandResponder(new CommandResponder() {
            public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
                PDU request=event.getPDU();
                if(request==null || request.getType()==PDU.REPORT || request.getType()==PDU.RESPONSE)return;
                if(event.getMessageProcessingModel()==MPv2c.ID && !community.equals(new OctetString(event.getSecurityName()).toString()))return;
                int requestNumber=requests.incrementAndGet();
                if(silent)return;
                if(requestNumber==1&&firstResponseDelayMillis>0){
                    try{Thread.sleep(firstResponseDelayMillis);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                }
                PDU response=request instanceof ScopedPDU ? (PDU)request.clone() : new PDU(request);
                response.clear();
                response.setRequestID(request.getRequestID());
                response.setType(PDU.RESPONSE);
                response.setErrorStatus(PDU.noError);response.setErrorIndex(0);
                if(request.getType()==PDU.GET){
                    for(int i=0;i<request.size();i++)if(rejectedGets.contains(request.get(i).getOid())){response.setErrorStatus(rejectedGetStatus);response.setErrorIndex(i+1);break;}
                    for(VariableBinding vb:request.getVariableBindings()){
                        Variable v=values.get(vb.getOid());
                        response.add(new VariableBinding(vb.getOid(),v==null?Null.noSuchInstance:(Variable)v.clone()));
                    }
                }else if(request.getType()==PDU.GETBULK || request.getType()==PDU.GETNEXT){
                    List<OID> cursors=request.getVariableBindings().stream().map(v->v.getOid()).toList();
                    int count=request.getType()==PDU.GETNEXT?1:Math.min(request.getMaxRepetitions(),20);
                    for(int row=0;row<count;row++){
                        List<OID> next=new ArrayList<>();
                        for(OID cursor:cursors){
                            var found=values.higherEntry(cursor);
                            if(wrongOrder){response.add(new VariableBinding(cursor,new Integer32(7)));next.add(cursor);}
                            else if(found==null){response.add(new VariableBinding(cursor,Null.endOfMibView));next.add(cursor);}
                            else{response.add(new VariableBinding(found.getKey(),(Variable)found.getValue().clone()));next.add(found.getKey());}
                        }
                        cursors=next;
                    }
                }else return; // SET is deliberately not implemented.
                try{
                    event.getMessageDispatcher().returnResponsePdu(event.getMessageProcessingModel(),event.getSecurityModel(),
                        event.getSecurityName(),event.getSecurityLevel(),response,event.getMaxSizeResponsePDU(),event.getStateReference(),new StatusInformation());
                    event.setProcessed(true);
                }catch(Exception ignored){ /* No credential-bearing packet logging. */ }
            }
        });
        huawei();
        agent.listen();
    }
    int port(){return transport.getListenAddress().getPort();}
    void set(String oid,Variable value){values.put(new OID(oid),value);}
    void text(String oid,String value){set(oid,new OctetString(value));}
    void huawei(){
        text("1.3.6.1.2.1.1.1.0","Huawei VRP synthetic protocol fixture; not hardware");
        set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.2011.2.23.999"));
        text("1.3.6.1.2.1.1.5.0","synthetic-snmp-switch");
        set("1.3.6.1.2.1.1.3.0",new TimeTicks(123456));
        text("1.3.6.1.2.1.47.1.1.1.1.7.101","main-board");
        set("1.3.6.1.2.1.47.1.1.1.1.5.101",new Integer32(3));
        text("1.3.6.1.2.1.47.1.1.1.1.11.101","SYNTHETIC-SERIAL");
        text("1.3.6.1.2.1.47.1.1.1.1.13.101","SYNTHETIC-MODEL");
        text("1.3.6.1.2.1.47.1.1.1.1.10.101","SYNTHETIC-VRP");
        set("1.3.6.1.4.1.2011.5.25.31.1.1.1.1.5.101",new Integer32(17));
        set("1.3.6.1.4.1.2011.5.25.31.1.1.1.1.7.101",new Integer32(42));
        set("1.3.6.1.4.1.2011.5.25.31.1.1.1.1.11.101",new Integer32(36));
        addPort(7,"Ethernet1/1","00:11:22:33:44:55");
    }
    void addPort(int index,String name,String mac){
        String i="."+index;
        set("1.3.6.1.2.1.2.2.1.1"+i,new Integer32(index));
        text("1.3.6.1.2.1.2.2.1.2"+i,name);
        set("1.3.6.1.2.1.2.2.1.6"+i,OctetString.fromHexString(mac));
        set("1.3.6.1.2.1.2.2.1.7"+i,new Integer32(1));
        set("1.3.6.1.2.1.2.2.1.8"+i,new Integer32(2));
        text("1.3.6.1.2.1.31.1.1.1.1"+i,name);
        set("1.3.6.1.2.1.31.1.1.1.6"+i,new Counter64(Long.parseUnsignedLong("9223372036854776000")));
        set("1.3.6.1.2.1.31.1.1.1.10"+i,new Counter64(123456789));
        set("1.3.6.1.2.1.31.1.1.1.15"+i,new Gauge32(10000));
        set("1.3.6.1.2.1.31.1.1.1.19"+i,new TimeTicks(100));
    }
    @Override public void close() throws Exception {agent.close();}
    public static void main(String[] args) throws Exception {
        String community=System.getenv("SNMP_MOCK_COMMUNITY"), auth=System.getenv("SNMP_MOCK_AUTH_PASSWORD"), privacy=System.getenv("SNMP_MOCK_PRIVACY_PASSWORD");
        if(community==null || auth==null || privacy==null)throw new IllegalArgumentException("Set SNMP_MOCK_COMMUNITY, SNMP_MOCK_AUTH_PASSWORD and SNMP_MOCK_PRIVACY_PASSWORD in the process environment.");
        var fixture=new SnmpUdpFixture("0.0.0.0",Integer.parseInt(System.getenv().getOrDefault("SNMP_MOCK_PORT","1161")),
            community,System.getenv().getOrDefault("SNMP_MOCK_USERNAME","synthetic-user"),auth,privacy);
        long began=System.nanoTime();
        fixture.set("1.3.6.1.2.1.1.3.0",new TimeTicks(100));
        fixture.set("1.3.6.1.2.1.31.1.1.1.6.7",new Counter64(1_000_000));
        fixture.set("1.3.6.1.2.1.31.1.1.1.10.7",new Counter64(500_000));
        var clock=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"synthetic-snmp-clock");t.setDaemon(true);return t;});
        clock.scheduleAtFixedRate(()->{
            long elapsedMillis=java.time.Duration.ofNanos(System.nanoTime()-began).toMillis();
            fixture.set("1.3.6.1.2.1.1.3.0",new TimeTicks((100+elapsedMillis/10)&0xffffffffL));
            fixture.set("1.3.6.1.2.1.31.1.1.1.6.7",new Counter64(1_000_000+elapsedMillis*125));
            fixture.set("1.3.6.1.2.1.31.1.1.1.10.7",new Counter64(500_000+elapsedMillis*625/10));
        },1,1,java.util.concurrent.TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{clock.shutdownNow();try{fixture.close();}catch(Exception ignored){}}));
        System.out.println("SYNTHETIC SNMP UDP fixture ready; port="+fixture.port()+"; read-only v2c/v3; synthetic RX 1Mbps/TX 0.5Mbps clock; no real device accessed");
        new CountDownLatch(1).await();
    }
}
