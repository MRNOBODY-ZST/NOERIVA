package io.noeriva.control.devices;

import java.net.InetAddress;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeviceSafetyTest {
    final String key=Base64.getEncoder().encodeToString(new byte[32]);
    @Test void ciphertextIsRandomAuthenticatedAndBoundToItsTenantDeviceAndSlot(){
        var vault=new CredentialVault(key);var secret=new DeviceProtocol.Secrets("private-community","auth-password",null,null);
        String first=vault.encrypt("org/device/snmp",secret),second=vault.encrypt("org/device/snmp",secret);
        assertThat(vault.ready()).isTrue();assertThat(first).doesNotContain("private-community","auth-password").isNotEqualTo(second);
        assertThat(vault.decrypt("org/device/snmp",first)).isEqualTo(secret);
        assertThatThrownBy(()->vault.decrypt("other/device/snmp",first)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->vault.decrypt("org/device/redfish",first)).isInstanceOf(RuntimeException.class);
        var bytes=Base64.getDecoder().decode(first);bytes[bytes.length-1]^=1;
        assertThatThrownBy(()->vault.decrypt("org/device/snmp",Base64.getEncoder().encodeToString(bytes))).isInstanceOf(RuntimeException.class);
        assertThat(secret.toString()).doesNotContain("private-community","auth-password");
    }
    @Test void missingKeyCannotStorePlaintextAndInvalidKeyFailsAtStartup(){
        assertThat(new CredentialVault("").ready()).isFalse();
        assertThatThrownBy(()->new CredentialVault("").encrypt("a",new DeviceProtocol.Secrets("secret",null,null,null))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new CredentialVault("too-short")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void allDnsAnswersMustBelongToAnAllowedNetwork() throws Exception {
        var policy=new TargetPolicy("10.0.0.0/8,192.168.0.0/16,fc00::/7");
        assertThat(policy.validateResolved("switch.local",List.of(InetAddress.getByName("10.3.2.1")))).isEqualTo("10.3.2.1");
        assertThat(policy.validateResolved("bmc.local",List.of(InetAddress.getByName("fd12::9")))).contains("fd12");
        assertThatThrownBy(()->policy.validateResolved("switch.local",List.of(InetAddress.getByName("10.3.2.1"),InetAddress.getByName("8.8.8.8")))).isInstanceOf(RuntimeException.class);
    }
    @Test void ipv6MetadataIsDeniedEvenInsideTheDefaultPrivateRange(){var policy=new TargetPolicy("fc00::/7");assertThatThrownBy(()->policy.validateResolved("bmc",List.of(InetAddress.getByName("fd00:ec2::254")))).isInstanceOf(DeviceProtocol.Failure.class);}
    @Test void googleMetadataIsDeniedWithoutBlockingOtherPrivateIpv6Devices() throws Exception {
        for(String cidrs:List.of("fc00::/7","::/0")){
            var policy=new TargetPolicy(cidrs);
            try {
                assertThatThrownBy(()->policy.validateResolved("bmc",List.of(InetAddress.getByName("fd20:ce::254")))).isInstanceOf(DeviceProtocol.Failure.class);
                assertThat(policy.validateResolved("bmc",List.of(InetAddress.getByName("fd20:ce::255")))).startsWith("fd20:ce:");
            } finally { policy.close(); }
        }
    }
    @Test void metadataMulticastWildcardAndMalformedHostsAreAlwaysRejected() throws Exception {
        var policy=new TargetPolicy("0.0.0.0/0,::/0");
        for(String ip:List.of("169.254.169.254","224.0.0.1","0.0.0.0","fe80::1","ff02::1","fd00:ec2::254"))
            assertThatThrownBy(()->policy.validateResolved("device.local",List.of(InetAddress.getByName(ip)))).isInstanceOf(RuntimeException.class);
        for(String host:List.of("http://10.0.0.1","admin@10.0.0.1","10.0.0.1/path","fe80::1%en0"," device.local"))
            assertThatThrownBy(()->policy.validateResolved(host,List.of(InetAddress.getByName("10.0.0.1")))) .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->policy.validateResolved("device",List.of())).isInstanceOf(RuntimeException.class);
    }
}
