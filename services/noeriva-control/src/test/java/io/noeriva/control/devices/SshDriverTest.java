package io.noeriva.control.devices;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SshDriverTest {
    final JsonMapper json=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    DeviceProtocol driver(){try{return (DeviceProtocol)Class.forName("io.noeriva.control.devices.SshDriver").getConstructor().newInstance();}catch(Exception e){throw new AssertionError("SSH driver must implement the bounded read-only protocol",e);}}
    DeviceProtocol.Target target(SshFixture f,String pin,String profile){
        return json.readValue(json.writeValueAsString(Map.of("host","must-not-resolve.invalid","address","127.0.0.1","port",f.port(),"protocol","SSH","username","synthetic-user","timeoutMillis",1500,"maxInterfaces",128,"sshProfile",profile,"sshHostKeySha256",pin)),DeviceProtocol.Target.class);
    }
    DeviceProtocol.Reading read(SshFixture f,String pin,String profile,String password)throws Exception{
        var d=driver();try{return d.read(target(f,pin,profile),new DeviceProtocol.Secrets(null,null,null,password)).block(Duration.ofSeconds(28));}finally{if(d instanceof AutoCloseable c)c.close();}
    }
    @Test void pinnedInteractiveImanaUsesFixedAddressAndFourReadCommands()throws Exception{
        try(var f=new SshFixture()){
            var r=read(f,f.pin,"HUAWEI_IMANA","synthetic-password");
            assertThat(r.identity().vendor()).isEqualTo("Huawei");assertThat(r.identity().firmware()).contains("7.35");
            assertThat(r.identity().model()).isEqualTo("SYNTHETIC RH2288 V2");assertThat(r.identity().serialNumber()).isEqualTo("SYNTHETIC-SERIAL");
            assertThat(r.health()).isEqualTo("HEALTHY");assertThat(r.metrics()).containsEntry("temperature_celsius",22d).containsEntry("power_watts",20d);
            assertThat(r.sensors()).hasSize(5).anySatisfy(s->{assertThat(s.metric()).isEqualTo("fan_rpm");assertThat(s.value()).isEqualTo(4224d);});
            assertThat(r.sensors()).filteredOn(s->s.label().equals("CPU Temp")||s.label().equals("PS Status")).allSatisfy(s->{assertThat(s.value()).isNull();assertThat(s.health()).isEqualTo("UNKNOWN");});
            assertThat(f.commands).containsExactly("ipmcget -d version","ipmcget -d fruinfo","ipmcget -d health","ipmcget -t sensor -d list");
            assertThat(f.passwords).hasValue(1);assertThat(r.facts().toString()).doesNotContain("synthetic-password","Product Manufacturer");
        }
    }
    @Test void wrongHostKeyNeverReceivesPassword()throws Exception{
        try(var f=new SshFixture()){
            assertThatThrownBy(()->read(f,"SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(new byte[32]),"HUAWEI_IMANA","synthetic-password"))
              .isInstanceOf(DeviceProtocol.Failure.class).satisfies(e->assertThat(((DeviceProtocol.Failure)e).code()).isEqualTo("SSH_HOST_KEY_MISMATCH"));
            assertThat(f.passwords).hasValue(0);assertThat(f.commands).isEmpty();
        }
    }
    @Test void wrongPasswordHasOneAttemptAndNoSecretInError()throws Exception{
        try(var f=new SshFixture()){
            assertThatThrownBy(()->read(f,f.pin,"HUAWEI_IMANA","wrong-secret"))
                .isInstanceOf(DeviceProtocol.Failure.class).hasMessageNotContaining("wrong-secret")
                .satisfies(e->assertThat(((DeviceProtocol.Failure)e).code()).isEqualTo("SSH_AUTHENTICATION_FAILED"));
            assertThat(f.passwords).hasValue(1);assertThat(f.commands).isEmpty();
        }
    }
    @Test void helpAngleBracketsAreNotPromptsAndPagerIsHandled()throws Exception{
        try(var f=new SshFixture()){
            f.replies.put("ipmcget -d version","help <dataitem>\r\n[PAGE]Active iMana Version: 7.38\r\n");
            assertThat(read(f,f.pin,"HUAWEI_IMANA","synthetic-password").identity().firmware()).contains("7.38");
            assertThat(f.commands).hasSize(4);
        }
    }
    @Test void excessiveCommandOutputIsRejected()throws Exception{
        try(var f=new SshFixture()){
            f.replies.put("ipmcget -d version","x".repeat(270000));
            assertThatThrownBy(()->read(f,f.pin,"HUAWEI_IMANA","synthetic-password"))
                .isInstanceOf(DeviceProtocol.Failure.class).satisfies(e->assertThat(((DeviceProtocol.Failure)e).code()).isEqualTo("SSH_OUTPUT_LIMIT"));
        }
    }
    @Test void aPeerOfferingOnlyLegacyCbcCannotReceiveCredentials()throws Exception{
        try(var f=new SshFixture()){
            f.server.setCipherFactories(List.of(org.apache.sshd.common.cipher.BuiltinCiphers.aes128cbc));
            assertThatThrownBy(()->read(f,f.pin,"HUAWEI_IMANA","synthetic-password")).isInstanceOf(DeviceProtocol.Failure.class);
            assertThat(f.passwords).hasValue(0);
        }
    }
    @Test void missingOptionalSensorCommandIsPartialRatherThanZero()throws Exception{
        try(var f=new SshFixture()){
            f.replies.remove("ipmcget -t sensor -d list");var r=read(f,f.pin,"HUAWEI_IMANA","synthetic-password");
            assertThat(r.sensors()).isEmpty();assertThat(r.metrics()).isEmpty();assertThat(r.qualityFlags()).contains("SSH_COMMAND_UNSUPPORTED");
        }
    }
    @Test void peerEchoedPasswordIsNotReturnedAsStructuredIdentity()throws Exception{
        try(var f=new SshFixture()){
            f.replies.put("ipmcget -d fruinfo","Product Name: synthetic-password\nProduct Serial Number: SYNTHETIC-SERIAL\n");
            assertThat(json.writeValueAsString(read(f,f.pin,"HUAWEI_IMANA","synthetic-password"))).doesNotContain("synthetic-password");
        }
    }
    @Test void hangingCommandUsesTheConfiguredDeadlineAndClosesPeer()throws Exception{
        try(var f=new SshFixture()){
            f.hang=true;long started=System.nanoTime();
            assertThatThrownBy(()->read(f,f.pin,"HUAWEI_IMANA","synthetic-password")).isInstanceOf(DeviceProtocol.Failure.class)
                .satisfies(e->assertThat(((DeviceProtocol.Failure)e).code()).isEqualTo("SSH_TIMEOUT"));
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(4));
        }
    }
    @Test void dellUsesTheSamePinnedShellWithNetworkCommandWhitelist()throws Exception{
        try(var f=new SshFixture()){
            f.prompt="SYNTHETIC-DELL#";f.replies.clear();f.replies.putAll(new SshProfileTest().dell());
            f.replies.put("show arp","Protocol Address Age(min) Hardware Address Interface VLAN CPU\nInternet 192.0.2.2 3 00:00:5e:00:53:02 Te 1/1/1 Vl 4 CP\n");
            f.replies.put("show lldp neighbors detail","No LLDP neighbors\n");
            var r=read(f,f.pin,"DELL_OS9","synthetic-password");assertThat(r.identity().vendor()).isEqualTo("Dell");assertThat(json.readTree(r.facts().get("neighborObservations")).size()).isEqualTo(1);
            assertThat(f.commands).containsExactly("show version","show inventory","show environment","show arp","show lldp neighbors detail");
        }
    }
    @Test void cancellationClosesTheSessionWithoutDroppedErrors()throws Exception{
        try(var f=new SshFixture()){
            f.hang=true;var d=driver();var dropped=new java.util.concurrent.CopyOnWriteArrayList<Throwable>();reactor.core.publisher.Hooks.onErrorDropped(dropped::add);
            try{
                var task=d.read(target(f,f.pin,"HUAWEI_IMANA"),new DeviceProtocol.Secrets(null,null,null,"synthetic-password")).subscribe(v->{},e->{});
                long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();while(f.commands.isEmpty()&&System.nanoTime()<end)Thread.sleep(10);
                assertThat(f.commands).isNotEmpty();task.dispose();
                end=System.nanoTime()+Duration.ofSeconds(2).toNanos();while(f.active.get()>0&&System.nanoTime()<end)Thread.sleep(10);
                assertThat(f.active).hasValue(0);assertThat(dropped).isEmpty();
            }finally{reactor.core.publisher.Hooks.resetOnErrorDropped();if(d instanceof AutoCloseable c)c.close();}
        }
    }
}
