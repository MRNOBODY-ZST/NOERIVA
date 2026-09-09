package io.noeriva.control.devices;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SshProfileTest {
    final JsonMapper json=new JsonMapper();
    Map<String,String> dell(){var r=new LinkedHashMap<String,String>();r.put("show version","Dell EMC Real Time Operating System Software\nDell EMC Application Software Version: 9.14(2.23)\nSystem Type: SYNTHETIC S6100-ON\n");r.put("show inventory","Serial Number : NA\nService Tag : SYNTHETIC-TAG\n");return r;}
    @Test void dellArpPreservesVlanAndSpacedPortWithoutInventingPhysicalPort(){
        var input=dell();input.put("show arp","Protocol Address Age(min) Hardware Address Interface VLAN CPU\nInternet 192.0.2.1 - 00:00:5e:00:53:01 - Vl 4 CP\nInternet 192.0.2.2 12 00:00:5e:00:53:02 Fo 1/1/2 Vl 4 CP\nInternet 192.0.2.3 1 00:00:5e:00:53:03 Te 1/5/1 - CP\nInternet 192.0.2.99 0 Incomplete Te 1/5/1 - CP\n");
        var r=SshProfiles.parse("DELL_OS9",input,Set.of());var n=json.readTree(r.facts().get("neighborObservations"));
        assertThat(n.size()).isEqualTo(3);assertThat(n.get(0).get("interfaceName").isNull()).isTrue();assertThat(n.get(0).get("ageMinutes").isNull()).isTrue();
        assertThat(n.get(1).get("vlan").asText()).isEqualTo("4");assertThat(n.get(1).get("interfaceName").asText()).isEqualTo("Fo 1/1/2");assertThat(n.get(1).get("ageMinutes").asLong()).isEqualTo(12);
        assertThat(r.identity().serialNumber()).isEqualTo("SYNTHETIC-TAG");assertThat(r.facts()).containsEntry("serialKind","serviceTag");
    }
    @Test void dellManagementUnitServiceTagIsSelectedByHeaderAndMarker(){
        var input=dell();
        String header="Unit Type                      Serial Number  Part Number  Rev  Piece Part ID            Rev  Svc Tag  Exprs Svc Code";
        String module=String.format("%-31s%-15s%-13s%-5s%-26s%-4s%-9s%s","1 SYNTHETIC-MODULE","NA","PART","A01","SYNTHETIC-PIECE","A01","MOD0001","00000");
        String management=String.format("%-31s%-15s%-13s%-5s%-26s%-4s%-9s%s","* 1 SYNTHETIC-S6100","NA","PART","A01","SYNTHETIC-PIECE","A01","SYN0001","00000");
        input.put("show inventory",header+"\n"+module+"\n"+management+"\n");
        var r=SshProfiles.parse("DELL_OS9",input,Set.of());
        assertThat(r.identity().serialNumber()).isEqualTo("SYN0001");assertThat(r.facts()).containsEntry("serialKind","serviceTag");
    }
    @Test void dellLldpKeepsMultipleNeighborsAndDoesNotMakeLocalIdIntoMac(){
        var input=dell();input.put("show lldp neighbors detail","""
            Local Interface Te 1/5/2 has 2 neighbors
            Remote Chassis ID Subtype: Locally assigned (7)
            Remote Chassis ID: 820972080
            Remote Port ID: synthetic-eth1
            Local Port ID: TenGigabitEthernet 1/5/2
            Remote TTL: 120
            Information valid for next 43 seconds
            Remote System Name: SYNTHETIC BMC
            Remote Management Address (IPv4): 192.0.2.20
            Remote Chassis ID Subtype: Mac address (4)
            Remote Chassis ID: 00:00:5e:00:53:02
            Remote Port ID: Gi0
            Local Port ID: TenGigabitEthernet 1/5/2
            Remote TTL: 60
            Information valid for next 0 seconds
            Remote System Name: SYNTHETIC Router
            Remote Management Address (IPv4): 192.0.2.21
            """);
        var r=SshProfiles.parse("DELL_OS9",input,Set.of());var n=json.readTree(r.facts().get("neighborObservations"));
        assertThat(n.size()).isEqualTo(2);assertThat(n.get(0).get("mac").isNull()).isTrue();assertThat(n.get(1).get("mac").asText()).isEqualTo("00:00:5e:00:53:02");
        assertThat(n.get(0).get("ttlSeconds").asLong()).isEqualTo(43);
        assertThat(n.get(1).get("interfaceName").asText()).isEqualTo("TenGigabitEthernet 1/5/2");assertThat(n.get(1).get("ttlSeconds").asLong()).isZero();
    }
    @Test void ciscoInventoryArpAndCdpDoNotReuseDellColumns(){
        var input=new LinkedHashMap<String,String>();input.put("show version","Cisco IOS XE Software, Version 17.09.08\ncisco ASR1002-X (2RU) processor\n");
        input.put("show inventory","NAME: \"Chassis\", DESCR: \"Cisco ASR1002-X Chassis\"\nPID: ASR1002-X, VID: V01, SN: SYNTHETIC-SERIAL\n");
        input.put("show ip arp","Protocol Address Age(min) Hardware Addr Type Interface\nInternet 192.0.2.2 3 0000.5e00.5302 ARPA GigabitEthernet0\n");
        input.put("show cdp neighbors detail","Device ID: SYNTHETIC-NEIGHBOR\nIP address: 192.0.2.3\nInterface: TenGigabitEthernet0/3/0, Port ID (outgoing port): Te1/5/1\nHoldtime : 139 sec\n");
        var r=SshProfiles.parse("CISCO_IOS_XE",input,Set.of());assertThat(r.identity().firmware()).isEqualTo("17.09.08");assertThat(r.identity().serialNumber()).isEqualTo("SYNTHETIC-SERIAL");
        var n=json.readTree(r.facts().get("neighborObservations"));assertThat(n.size()).isEqualTo(2);assertThat(n.get(0).get("mac").asText()).isEqualTo("00:00:5e:00:53:02");assertThat(n.get(1).get("source").asText()).isEqualTo("CDP");assertThat(n.get(1).get("portId").asText()).isEqualTo("Te1/5/1");
    }
    @Test void combinedNeighborBudgetStopsAt256AndFlagsIncompleteCatalog(){
        var input=dell();var rows=new StringBuilder("Protocol Address Age(min) Hardware Address Interface VLAN CPU\n");
        for(int i=0;i<300;i++)rows.append("Internet 192.0.2.").append(i%254+1).append(" 3 00:00:5e:00:53:01 Te 1/1/").append(i).append(" Vl 4 CP\n");
        input.put("show arp",rows.toString());var r=SshProfiles.parse("DELL_OS9",input,Set.of());assertThat(json.readTree(r.facts().get("neighborObservations")).size()).isEqualTo(256);assertThat(r.qualityFlags()).contains("SSH_NEIGHBOR_LIMIT");
    }
    @Test void healthFailureHasNoSuccessfulHealthCapability(){
        var r=SshProfiles.parse("HUAWEI_IMANA",Map.of("ipmcget -d version","Active iMana Version: 7.35\n","ipmcget -d health","Unrecognized status\n"),Set.of());
        assertThat(r.health()).isEqualTo("UNKNOWN");assertThat(r.capabilities()).doesNotContain("health");assertThat(r.qualityFlags()).contains("SSH_HEALTH_UNRECOGNIZED");
    }
    @Test void configuredProfileDoesNotFabricateIdentityForOtherDevices(){
        assertThatThrownBy(()->SshProfiles.parse("HUAWEI_IMANA",Map.of("ipmcget -d version","Linux synthetic shell\n"),Set.of())).isInstanceOf(DeviceProtocol.Failure.class);
    }
    @Test void imanaCriticalSensorOverridesNominalSystemHealthAndMissingValuesRemainUnknown(){
        var input=Map.of("ipmcget -d version","Active iMana Version: 7.38\n","ipmcget -d health","System in health state\n","ipmcget -t sensor -d list","CPU Temp | 95 | degrees C | cr | na | na | na | 80 | 90 | 100 | 2 | 2\nPower1 | na | Watts | ok | na | na | na | na | na | na | 0 | 0\n");
        var r=SshProfiles.parse("HUAWEI_IMANA",input,Set.of());
        assertThat(r.health()).isEqualTo("CRITICAL");assertThat(r.metrics()).doesNotContainKey("power_watts");
        assertThat(r.sensors()).filteredOn(s->s.label().equals("Power1")).singleElement().satisfies(s->{assertThat(s.value()).isNull();assertThat(s.health()).isEqualTo("UNKNOWN");});
    }
}
