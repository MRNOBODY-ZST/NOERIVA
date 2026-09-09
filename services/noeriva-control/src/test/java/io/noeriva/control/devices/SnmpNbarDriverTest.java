package io.noeriva.control.devices;

import io.noeriva.control.applications.ApplicationModels.*;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.*;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SnmpNbarDriverTest {
    static final String P="1.3.6.1.4.1.9.9.244.1.2.1.1.", S="1.3.6.1.4.1.9.9.244.1.1.1.1.";
    void setup(SnmpUdpFixture f){f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.9.1.1525"));f.set(S+"1.7",new Integer32(1));f.set(S+"2.7",new TimeTicks(10));row(f,7,42,"synthetic-http");}
    void row(SnmpUdpFixture f,int index,int protocol,String name){String suffix=index+"."+protocol;f.text(P+"2."+suffix,name);for(int c:List.of(7,8,9,10))f.set(P+c+"."+suffix,new Counter64(Long.parseUnsignedLong("18446744073709550000")));f.set(P+"11."+suffix,new Gauge32(123));f.set(P+"12."+suffix,new Gauge32(0));}
    @SuppressWarnings("unchecked") Sample read(SnmpUdpFixture f,List<Integer> indices,int rows,boolean v3)throws Exception{
        Class<?> type=Class.forName("io.noeriva.control.devices.SnmpNbarDriver");Object driver=type.getConstructor().newInstance();
        var t=new DeviceProtocol.Target("never-resolve.invalid","127.0.0.1",f.port(),"SNMP",v3?"synthetic-user":"",v3?"3":"2c",v3?"authPriv":"",v3?"SHA256":"",v3?"AES128":"","","SYSTEM","",150,128);
        try{return ((Mono<Sample>)type.getMethod("read",DeviceProtocol.Target.class,DeviceProtocol.Secrets.class,List.class,int.class).invoke(driver,t,new DeviceProtocol.Secrets("synthetic-community","auth-test-pass","priv-test-pass",null),indices,rows)).block(Duration.ofSeconds(28));}
        finally{((AutoCloseable)driver).close();}
    }
    @Test void actualUdpUsesSelectedCompositeIndexAndPreservesUnsignedCounters()throws Exception{try(var f=new SnmpUdpFixture()){setup(f);row(f,8,42,"wrong-interface");var sample=read(f,List.of(7),128,false);assertThat(sample.rows()).singleElement().satisfies(r->{assertThat(r.application()).isEqualTo("synthetic-http");assertThat(r.inBytes()).isEqualTo("18446744073709550000");assertThat(r.reportedInBps()).isEqualTo(123000d);assertThat(r.reportedOutBps()).isZero();assertThat(r.enableTime()).isEqualTo("10");});assertThat(sample.sysUptimeTicks()).isEqualTo("123456");}}
    @Test void realUsmV3CollectionUsesCredentialAlgorithms()throws Exception{try(var f=new SnmpUdpFixture()){setup(f);var sample=read(f,List.of(7),128,true);assertThat(sample.engineId()).isNotBlank();assertThat(sample.rows()).hasSize(1);}}
    @Test void disabledProtocolDiscoveryIsReportedWithoutFakeZeroRows()throws Exception{try(var f=new SnmpUdpFixture()){setup(f);f.set(S+"1.7",new Integer32(2));var sample=read(f,List.of(7),128,false);assertThat(sample.rows()).isEmpty();assertThat(sample.qualityFlags()).contains("NBAR_DISABLED");}}
    @Test void rowLimitIsSharedAcrossInterfacesAndVisible()throws Exception{try(var f=new SnmpUdpFixture()){setup(f);row(f,7,43,"second");var sample=read(f,List.of(7),1,false);assertThat(sample.rows()).hasSize(1);assertThat(sample.qualityFlags()).contains("NBAR_ROW_LIMIT");assertThat(f.requests.get()).isLessThan(20);}}
    @Test void unsupportedAnd32BitCountersAreNeverPromotedToValidHcData()throws Exception{try(var f=new SnmpUdpFixture()){setup(f);f.set(P+"9.7.42",new Counter32(12));f.set(P+"10.7.42",new Counter32(34));var sample=read(f,List.of(7),128,false);assertThat(sample.rows()).singleElement().satisfies(r->{assertThat(r.inBytes()).isNull();assertThat(r.outBytes()).isNull();assertThat(r.qualityFlags()).contains("NBAR_COUNTER_UNAVAILABLE");});}}
    void enabledInterface(SnmpUdpFixture f,int index,int protocols){f.set(S+"1."+index,new Integer32(1));f.set(S+"2."+index,new TimeTicks(10));f.addPort(index,"synthetic-interface-"+index,"00:11:22:33:44:"+String.format("%02x",index));for(int p=1;p<=protocols;p++)row(f,index,p,"synthetic-application-"+p);}
    @Test void twoBusyInterfacesReceiveFairSharesWithinTheGlobalBudget()throws Exception{try(var f=new SnmpUdpFixture()){setup(f);enabledInterface(f,8,300);enabledInterface(f,9,300);var sample=read(f,List.of(8,9),256,false);assertThat(sample.rows()).hasSize(256);assertThat(sample.rows().stream().filter(r->r.interfaceIndex()==8)).hasSize(128);assertThat(sample.rows().stream().filter(r->r.interfaceIndex()==9)).hasSize(128);assertThat(sample.qualityFlags()).contains("NBAR_ROW_LIMIT").doesNotContain("SNMP_VARIABLE_BUDGET");}}
    @Test void unusedFirstInterfaceQuotaIsAvailableToTheNextInterface()throws Exception{try(var f=new SnmpUdpFixture()){setup(f);enabledInterface(f,8,2);enabledInterface(f,9,200);var sample=read(f,List.of(8,9),128,false);assertThat(sample.rows()).hasSize(128);assertThat(sample.rows().stream().filter(r->r.interfaceIndex()==8)).hasSize(2);assertThat(sample.rows().stream().filter(r->r.interfaceIndex()==9)).hasSize(126);assertThat(sample.qualityFlags()).contains("NBAR_ROW_LIMIT").doesNotContain("SNMP_VARIABLE_BUDGET");}}
    @Test void disabledInterfacesDoNotReserveQuotaFromEnabledInterfaces()throws Exception{try(var f=new SnmpUdpFixture()){setup(f);enabledInterface(f,8,200);enabledInterface(f,9,200);f.set(S+"1.8",new Integer32(2));for(var indices:List.of(List.of(8,9),List.of(9,8))){var sample=read(f,indices,128,false);assertThat(sample.rows()).hasSize(128).allMatch(r->r.interfaceIndex()==9);assertThat(sample.qualityFlags()).contains("NBAR_DISABLED","NBAR_ROW_LIMIT");}}}
}
