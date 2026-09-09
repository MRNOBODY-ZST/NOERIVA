package io.noeriva.control.devices;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.noeriva.control.devices.DeviceAccessModels.*;

class ConfigurationReaderTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private final DeviceAccessStore store=mock(DeviceAccessStore.class);
    private final CredentialVault vault=new CredentialVault(Base64.getEncoder().encodeToString(new byte[32]));
    private Stored stored(String slot,String profile,int port,String pin){
        return stored(slot,profile,port,pin,true);
    }
    private Stored stored(String slot,String profile,int port,String pin,boolean enabled){
        var settings=new Settings("127.0.0.1",port,60,1500,128,"synthetic-user",null,null,null,null,null,"SYSTEM",null,false,false,false,true,profile,pin);
        return new Stored("org","device",slot,1,enabled,settings,vault.encrypt("org/device/"+slot,new DeviceProtocol.Secrets(null,null,null,"synthetic-password")),"SUCCESS",Instant.now(),Instant.now(),null,null,null,null,null,null,null,1,"epoch");
    }
    @Test void networkSnapshotUsesOnlyPinnedReadConfigurationCommand()throws Exception{
        try(var f=new SshFixture()){
            f.prompt="SYNTHETIC-DELL#";f.replies.clear();
            f.replies.put("show running-config","Building configuration...\nCurrent configuration : 128 bytes\n!\nversion 9.14\nhostname synthetic-switch\ninterface TenGigabitEthernet 1/1/1\n no shutdown\n!\nend\n");
            when(store.list("org","device")).thenReturn(Flux.just(stored("ssh","DELL_OS9",f.port(),f.pin)));
            var policy=new TargetPolicy("127.0.0.0/8");try{
                var capture=new ConfigurationReader(store,vault,policy,List.of(),json).read("org","device").block(Duration.ofSeconds(10));
                assertThat(capture.source()).isEqualTo("SSH_RUNNING_CONFIGURATION");
                assertThat(capture.content()).contains("hostname synthetic-switch","interface TenGigabitEthernet");
                assertThat(f.commands).containsExactly("show running-config");assertThat(f.passwords).hasValue(1);
            }finally{policy.close();}
        }
    }
    @Test void authorizationErrorCannotBecomeASuccessfulConfiguration()throws Exception{
        try(var f=new SshFixture()){
            f.prompt="SYNTHETIC-CISCO#";f.replies.clear();
            f.replies.put("show running-config","% Authorization failed. This account may not inspect the running configuration.\n");
            when(store.list("org","device")).thenReturn(Flux.just(stored("ssh","CISCO_IOS_XE",f.port(),f.pin)));
            var policy=new TargetPolicy("127.0.0.0/8");try{
                assertThatThrownBy(()->new ConfigurationReader(store,vault,policy,List.of(),json).read("org","device").block(Duration.ofSeconds(10)))
                    .isInstanceOf(DeviceProtocol.Failure.class);
            }finally{policy.close();}
        }
    }
    @Test void baselineExcludesChangingSensorCountersAndTimestamps(){
        var sample=new AtomicReference<>(reading(Instant.now(),50d,"1000"));
        DeviceProtocol driver=new DeviceProtocol(){public String protocol(){return "REDFISH";}public Mono<Reading> read(Target t,Secrets s){return Mono.just(sample.get());}};
        when(store.list("org","device")).thenReturn(Flux.just(stored("redfish",null,443,null)));
        var policy=new TargetPolicy("127.0.0.0/8");try{
            var reader=new ConfigurationReader(store,vault,policy,List.of(driver),json);
            var before=reader.read("org","device").block(Duration.ofSeconds(5));
            sample.set(reading(Instant.now().plusSeconds(60),65d,"8000"));
            var after=reader.read("org","device").block(Duration.ofSeconds(5));
            assertThat(after.content()).isEqualTo(before.content());assertThat(after.source()).isEqualTo("REDFISH_DEVICE_BASELINE");
            assertThat(after.content()).doesNotContain("temperature_celsius","observedAt","inOctets","uptime");
        }finally{policy.close();}
    }
    @Test void snmpWinsOverEnabledSshAndRedfishAndOnlyCapturesStableBaseline()throws Exception{
        try(var ssh=new SshFixture()){
            var sample=new AtomicReference<>(reading(Instant.now(),50d,"1000"));
            DeviceProtocol snmp=new DeviceProtocol(){public String protocol(){return "SNMP";}public Mono<Reading> read(Target t,Secrets s){return Mono.just(sample.get());}};
            DeviceProtocol redfish=mock(DeviceProtocol.class);when(redfish.protocol()).thenReturn("REDFISH");
            when(store.list("org","device")).thenReturn(Flux.just(stored("ssh","DELL_OS9",ssh.port(),ssh.pin),stored("redfish",null,443,null),stored("snmp",null,161,null)));
            var policy=new TargetPolicy("127.0.0.0/8");try{
                var reader=new ConfigurationReader(store,vault,policy,List.of(snmp,redfish),json);
                var before=reader.read("org","device").block(Duration.ofSeconds(5));
                sample.set(reading(Instant.now().plusSeconds(60),70d,"99000"));
                var after=reader.read("org","device").block(Duration.ofSeconds(5));
                assertThat(after.source()).isEqualTo("SNMP_DEVICE_BASELINE");
                assertThat(after.content()).isEqualTo(before.content()).contains("DEVICE_IDENTITY_AND_INTERFACE_BASELINE","SERIAL").doesNotContain("temperature_celsius","observedAt","inOctets","uptime");
                verify(redfish,never()).read(any(),any());assertThat(ssh.passwords).hasValue(0);assertThat(ssh.commands).isEmpty();
            }finally{policy.close();}
        }
    }
    @Test void snmpFailureNeverStartsFallbackSshOrRedfish()throws Exception{
        try(var ssh=new SshFixture()){
            DeviceProtocol snmp=mock(DeviceProtocol.class);when(snmp.protocol()).thenReturn("SNMP");when(snmp.read(any(),any())).thenReturn(Mono.error(new DeviceProtocol.Failure("SNMP_READ_FAILED","safe")));
            DeviceProtocol redfish=mock(DeviceProtocol.class);when(redfish.protocol()).thenReturn("REDFISH");
            when(store.list("org","device")).thenReturn(Flux.just(stored("ssh","DELL_OS9",ssh.port(),ssh.pin),stored("redfish",null,443,null),stored("snmp",null,161,null)));
            var policy=new TargetPolicy("127.0.0.0/8");try{
                assertThatThrownBy(()->new ConfigurationReader(store,vault,policy,List.of(snmp,redfish),json).read("org","device").block(Duration.ofSeconds(5))).isInstanceOfSatisfying(DeviceProtocol.Failure.class,e->assertThat(e.code()).isEqualTo("SNMP_READ_FAILED"));
                verify(redfish,never()).read(any(),any());assertThat(ssh.passwords).hasValue(0);assertThat(ssh.commands).isEmpty();
            }finally{policy.close();}
        }
    }
    @Test void disabledConnectionsAreNotResolvedDecryptedOrRead(){
        when(store.list("org","device")).thenReturn(Flux.just(stored("snmp",null,161,null,false),stored("ssh","DELL_OS9",22,null,false),stored("redfish",null,443,null,false)));
        var policy=mock(TargetPolicy.class);var unusedVault=mock(CredentialVault.class);
        assertThatThrownBy(()->new ConfigurationReader(store,unusedVault,policy,List.of(),json).read("org","device").block()).isInstanceOfSatisfying(DeviceProtocol.Failure.class,e->assertThat(e.code()).isEqualTo("CONFIGURATION_UNSUPPORTED"));
        verifyNoInteractions(policy,unusedVault);
    }
    private DeviceProtocol.Reading reading(Instant at,double temperature,String counter){
        return new DeviceProtocol.Reading(at,new DeviceProtocol.Identity("Synthetic","BMC","fixture","Server","SERIAL","v1",null,"bmc","Management controller"),"HEALTHY",Map.of("temperature_celsius",temperature),List.of(),List.of(new DeviceProtocol.Port("1","eth0","00:00:5e:00:53:01","1000000000","UP","UP",counter,counter,null,64,"fixture")),List.of(),List.of(),Map.of("uptime",counter));
    }
}
