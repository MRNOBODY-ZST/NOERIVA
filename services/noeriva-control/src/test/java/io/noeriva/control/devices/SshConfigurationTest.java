package io.noeriva.control.devices;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SshConfigurationTest {
    final JsonMapper json=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    DeviceAccessModels.Save input(String profile,String pin){
        return json.readValue(json.writeValueAsString(Map.of("enabled",false,"revision",0,"host","192.0.2.9","port",22,"intervalSeconds",60,"timeoutMillis",1500,"maxInterfaces",128,"username","synthetic-user","sshProfile",profile,"sshHostKeySha256",pin)),DeviceAccessModels.Save.class);
    }
    @Test void sshSettingsHaveAnExplicitProfileAndPin(){
        var save=input("HUAWEI_IMANA","SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(new byte[32]));
        assertThatCode(()->DeviceAccessService.settings("ssh",save)).doesNotThrowAnyException();
        assertThat(json.writeValueAsString(DeviceAccessService.settings("ssh",save))).contains("HUAWEI_IMANA","SHA256:");
    }
    @Test void oldRedfishSettingsRemainReadableWithoutSshFields(){
        var old=new DeviceAccessModels.Settings("192.0.2.9",443,60,1000,128,"synthetic-user","","","","","","SYSTEM","",false,false,false,true);
        var value=(tools.jackson.databind.node.ObjectNode)json.readTree(json.writeValueAsString(old));value.remove("sshProfile");value.remove("sshHostKeySha256");
        var result=json.readValue(value.toString(),DeviceAccessModels.Settings.class);
        assertThat(result.target("REDFISH","192.0.2.9").tlsMode()).isEqualTo("SYSTEM");
        assertThat(result.sshProfile()).isNull();assertThat(result.sshHostKeySha256()).isNull();
        assertThat(DeviceAccessService.sameIdentity(old,result)).isTrue();
    }
    @Test void profileAndPinChangesInvalidateRetainedSshPasswords(){
        String pin="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
        var first=DeviceAccessService.settings("ssh",input("HUAWEI_IMANA",pin));
        var changed=DeviceAccessService.settings("ssh",input("DELL_OS9",pin));
        assertThat(DeviceAccessService.sameIdentity(first,changed)).isFalse();
        byte[] bytes=new byte[32];bytes[0]=1;var changedPin=DeviceAccessService.settings("ssh",input("HUAWEI_IMANA","SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(bytes)));
        assertThat(DeviceAccessService.sameIdentity(first,changedPin)).isFalse();
        assertThatThrownBy(()->DeviceAccessService.merge("ssh",changed,null,new DeviceProtocol.Secrets(null,null,null,null))).isInstanceOf(io.noeriva.control.ApiException.class);
    }
    @Test void arbitraryProfilesAndNonCanonicalPinsAreRejected(){
        String pin="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
        for(String profile:List.of("GENERIC_SHELL","ipmcset","HUAWEI_IMANA\nshow running-config"))assertThatThrownBy(()->DeviceAccessService.settings("ssh",input(profile,pin))).isInstanceOf(io.noeriva.control.ApiException.class);
        for(String invalid:List.of("",pin+"=","MD5:00:00",pin.substring(0,pin.length()-1)+"B"))assertThatThrownBy(()->DeviceAccessService.settings("ssh",input("HUAWEI_IMANA",invalid))).isInstanceOf(io.noeriva.control.ApiException.class);
    }
}
