package io.noeriva.control.devices;

import io.noeriva.control.*;
import io.noeriva.query.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;
import static io.noeriva.control.SecurityConfiguration.Operator;
import static io.noeriva.control.devices.DeviceAccessModels.*;

@Service
public class DeviceAccessService {
    private final DeviceAccessStore store;private final CredentialVault vault;private final TargetPolicy policy;private final DevicePublisher publisher;
    private final DeviceFailureEvents failureEvents;
    private final Map<String,DeviceProtocol> drivers;private final boolean collectorEnabled;
    private final QueryAdmission admission=new QueryAdmission(16,Duration.ofSeconds(65));
    public DeviceAccessService(DeviceAccessStore store,CredentialVault vault,TargetPolicy policy,List<DeviceProtocol> drivers,DevicePublisher publisher,Environment env,DeviceFailureEvents failureEvents){
        this.failureEvents=failureEvents;this.store=store;this.vault=vault;this.policy=policy;this.publisher=publisher;this.drivers=new HashMap<>();drivers.forEach(d->this.drivers.put(d.protocol(),d));
        collectorEnabled=env.getProperty("NOERIVA_DEVICE_POLLING_AVAILABLE",Boolean.class,false);
    }
    private static void role(Operator user,String...roles){if(Arrays.stream(roles).noneMatch(user.roles()::contains))throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","This action is not available to your role");}
    private static void slot(String slot){if(!Set.of("snmp","redfish","ssh").contains(slot))throw ApiException.missing();}
    private static String text(String value){return value==null?"":value.trim();}
    private static boolean supplied(String value){return value!=null&&!value.isEmpty();}
    private static void invalid(String message){throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_DEVICE_ACCESS",message);}
    public Mono<Management> management(Operator user,String id){role(user,"ADMIN","OPERATOR","VIEWER");return store.management(user.organizationId(),id);}
    public Mono<Management> update(Operator user,String id,Update input){role(user,"ADMIN","OPERATOR");return store.update(user.organizationId(),user.username(),id,input);}
    public Mono<Models.Items<ConnectionView>> connections(Operator user,String id){role(user,"ADMIN");return store.required(user.organizationId(),id).thenMany(store.list(user.organizationId(),id)).map(DeviceAccessModels::view).collectList().map(items->new Models.Items<>(items,Instant.now()));}
    public Mono<Models.Items<CollectionView>> collection(Operator user,String id){role(user,"ADMIN","OPERATOR","VIEWER");return store.required(user.organizationId(),id).thenMany(store.list(user.organizationId(),id)).map(DeviceAccessModels::collection).collectList().map(items->new Models.Items<>(items,Instant.now()));}
    public Mono<ConnectionView> save(Operator user,String id,String slot,Save input){
        role(user,"ADMIN");slot(slot);
        return store.required(user.organizationId(),id).then(store.get(user.organizationId(),id,slot).map(Optional::of).defaultIfEmpty(Optional.empty())).flatMap(old->{
            if((old.isEmpty()?0:old.get().revision())!=input.revision())return Mono.error(ApiException.conflict());
            Settings raw=settings(slot,input);String scope=user.organizationId()+"/"+id+"/"+slot;
            DeviceProtocol.Secrets retained=old.isPresent()&&sameIdentity(raw,old.get().settings())?vault.decrypt(scope,old.get().ciphertext()):new DeviceProtocol.Secrets(null,null,null,null);
            DeviceProtocol.Secrets merged=merge(slot,raw,input.secrets(),retained);
            Settings settings=new Settings(raw.host(),raw.port(),raw.intervalSeconds(),raw.timeoutMillis(),raw.maxInterfaces(),raw.username(),raw.snmpVersion(),raw.securityLevel(),raw.authProtocol(),raw.privacyProtocol(),raw.contextName(),raw.tlsMode(),raw.certificateSha256(),supplied(merged.community()),supplied(merged.authPassword()),supplied(merged.privacyPassword()),supplied(merged.password()),raw.sshProfile(),raw.sshHostKeySha256());
            return store.save(user.organizationId(),user.username(),id,slot,input.revision(),input.enabled(),settings,vault.encrypt(scope,merged)).map(DeviceAccessModels::view);
        });
    }
    static Settings settings(String slot,Save input){
        try{TargetPolicy.validateHost(input.host());}catch(DeviceProtocol.Failure failure){invalid(failure.getMessage());}
        if(input.port()<1||input.port()>65535||input.intervalSeconds()<15||input.intervalSeconds()>86400||input.timeoutMillis()<250||input.timeoutMillis()>10000||input.maxInterfaces()<1||input.maxInterfaces()>256)invalid("The target port or collection budget is outside its allowed range");
        String username=text(input.username()),version=text(input.snmpVersion()),level=text(input.securityLevel()),auth=text(input.authProtocol()),privacy=text(input.privacyProtocol()),context=text(input.contextName()),tls=text(input.tlsMode()),pin=text(input.certificateSha256()).replace(":","").toLowerCase(Locale.ROOT);
        String sshProfile=null,sshPin=null;
        if(slot.equals("snmp")){
            if(!Set.of("2c","3").contains(version))invalid("SNMP version must be 2c or 3");
            tls="";pin="";
            if(version.equals("2c")){username="";level="";auth="";privacy="";context="";}
            else{
                if(username.isEmpty())invalid("SNMPv3 requires a user name");
                if(!Set.of("noAuthNoPriv","authNoPriv","authPriv").contains(level))invalid("Select an explicit SNMPv3 security level");
                if(level.equals("noAuthNoPriv")){auth="";privacy="";}
                else{
                    if(!Set.of("MD5","SHA1","SHA256","SHA512").contains(auth))invalid("Unsupported SNMP authentication algorithm; no automatic downgrade is performed");
                    if(level.equals("authPriv")){if(!Set.of("AES128","DES").contains(privacy))invalid("Supported privacy algorithms are AES128 and explicit legacy DES");}
                    else privacy="";
                }
            }
        }else if(slot.equals("ssh")){
            version="";level="";auth="";privacy="";context="";tls="";pin="";
            sshProfile=text(input.sshProfile());sshPin=text(input.sshHostKeySha256());
            if(username.isEmpty()||username.length()>120||username.chars().anyMatch(Character::isISOControl))invalid("SSH requires a bounded user name without control characters");
            if(!Set.of("HUAWEI_IMANA","DELL_OS9","CISCO_IOS_XE").contains(sshProfile))invalid("Select a supported read-only SSH profile");
            if(!validSshPin(sshPin))invalid("Enter a canonical OpenSSH SHA256 host-key fingerprint");
        }else{
            version="";level="";auth="";privacy="";context="";
            if(username.isEmpty())invalid("Redfish requires an HTTPS user name");
            if(!Set.of("SYSTEM","PINNED").contains(tls))invalid("TLS mode must be SYSTEM or PINNED");
            if(tls.equals("PINNED")&&!pin.matches("[a-f0-9]{64}"))invalid("Enter the SHA-256 fingerprint of the Redfish leaf certificate");
            if(tls.equals("SYSTEM"))pin="";
        }
        return new Settings(input.host(),input.port(),input.intervalSeconds(),input.timeoutMillis(),input.maxInterfaces(),username,version,level,auth,privacy,context,tls,pin,false,false,false,false,sshProfile,sshPin);
    }
    static boolean validSshPin(String pin){
        if(pin==null||!pin.matches("SHA256:[A-Za-z0-9+/]{43}"))return false;
        try{byte[] digest=Base64.getDecoder().decode(pin.substring(7));return digest.length==32&&Base64.getEncoder().withoutPadding().encodeToString(digest).equals(pin.substring(7));}catch(IllegalArgumentException e){return false;}
    }
    static boolean sameIdentity(Settings a,Settings b){return a.host().equals(b.host())&&a.port()==b.port()&&a.username().equals(b.username())&&a.snmpVersion().equals(b.snmpVersion())&&a.securityLevel().equals(b.securityLevel())&&a.authProtocol().equals(b.authProtocol())&&a.privacyProtocol().equals(b.privacyProtocol())&&a.contextName().equals(b.contextName())&&a.tlsMode().equals(b.tlsMode())&&a.certificateSha256().equals(b.certificateSha256())&&Objects.equals(a.sshProfile(),b.sshProfile())&&Objects.equals(a.sshHostKeySha256(),b.sshHostKeySha256());}
    static DeviceProtocol.Secrets merge(String slot,Settings settings,SecretInput input,DeviceProtocol.Secrets old){
        SecretInput next=input==null?new SecretInput(null,null,null,null):input;
        String community=secret(next.community(),old.community()),auth=secret(next.authPassword(),old.authPassword()),privacy=secret(next.privacyPassword(),old.privacyPassword()),password=secret(next.password(),old.password());
        if(slot.equals("redfish")||slot.equals("ssh")){if(!supplied(password))invalid("Re-enter the protocol password for a new or changed credential identity");return new DeviceProtocol.Secrets(null,null,null,password);}
        if(settings.snmpVersion().equals("2c")){if(!supplied(community))invalid("Re-enter the read-only community for a new or changed credential identity");return new DeviceProtocol.Secrets(community,null,null,null);}
        if(settings.securityLevel().equals("noAuthNoPriv"))return new DeviceProtocol.Secrets(null,null,null,null);
        if(auth==null||auth.length()<8)invalid("SNMPv3 authentication passphrase requires at least eight characters and must be re-entered when the identity changes");
        if(settings.securityLevel().equals("authPriv")){if(privacy==null||privacy.length()<8)invalid("SNMPv3 privacy passphrase requires at least eight characters");return new DeviceProtocol.Secrets(null,auth,privacy,null);}
        return new DeviceProtocol.Secrets(null,auth,null,null);
    }
    static String secret(String next,String old){if(!supplied(next))return old;if(next.length()>1024||next.matches("[*•●]+")||next.indexOf('\u0000')>=0)invalid("Supply the original secret rather than a masked placeholder");return next;}
    public Mono<ConnectionView> state(Operator user,String id,String slot,State input){role(user,"ADMIN");slot(slot);return store.required(user.organizationId(),id).then(store.state(user.organizationId(),user.username(),id,slot,input)).map(DeviceAccessModels::view);}
    public Mono<ConnectionView> read(Operator user,String id,String slot,Revision input,boolean publish){
        role(user,"ADMIN");slot(slot);return store.required(user.organizationId(),id).then(store.get(user.organizationId(),id,slot).switchIfEmpty(Mono.error(ApiException.missing())))
            .flatMap(v->{if(v.revision()!=input.revision())return Mono.error(ApiException.conflict());return store.audit(user.organizationId(),user.username(),publish?"DEVICE_COLLECTION_REQUESTED":"DEVICE_TEST_REQUESTED",id+"/"+slot).then(poll(v,publish,false));}).map(DeviceAccessModels::view);
    }
    public Mono<Stored> poll(Stored expected,boolean publish,boolean scheduled){
        if(publish&&!expected.enabled())return Mono.error(new ApiException(HttpStatus.CONFLICT,"DEVICE_COLLECTION_DISABLED","Enable this connection before publishing a collection; an explicit connection test remains available"));
        return admission.execute(QueryLane.INTERACTIVE_STATE,()->store.acquire(expected,scheduled).flatMap(v->{
            Mono<DeviceProtocol.Reading> read=Mono.defer(()->{
                DeviceProtocol driver=drivers.get(v.protocol());if(driver==null)return Mono.error(new DeviceProtocol.Failure("PROTOCOL_UNAVAILABLE","No driver is installed for the configured protocol"));
                DeviceProtocol.Secrets secrets=vault.decrypt(v.scope(),v.ciphertext());return policy.resolve(v.settings().host()).flatMap(address->driver.read(v.settings().target(v.protocol(),address),secrets)).timeout(Duration.ofSeconds(30));
            });
            return read.flatMap(r->publish?publisher.publish(v,r).timeout(Duration.ofSeconds(24)).onErrorMap(e->new DeviceProtocol.Failure("PUBLICATION_FAILED","The device responded, but storage publication did not complete; some stores may contain this sample")):Mono.just(r))
                .flatMap(r->store.finish(v,r,publish,"","").flatMap(done->failureEvents==null||!publish?Mono.just(done):failureEvents.recovered(expected,done).onErrorComplete().thenReturn(done)))
                .onErrorResume(error->{String code=error instanceof DeviceProtocol.Failure f?f.code():error instanceof ApiException e?e.code:"COLLECTION_FAILED";
                    String message=error instanceof DeviceProtocol.Failure f?f.getMessage():error instanceof ApiException e?e.getMessage():"The read did not complete within its protocol and storage budgets";
                    return store.finish(v,null,false,code,message).flatMap(done->failureEvents==null||!publish?Mono.just(done):failureEvents.failed(expected,done,code).onErrorComplete().thenReturn(done));});
        })).cache(); // Once started, the bounded job completes even if its HTTP reader navigates away.
    }
    public Map<String,Object> support(){
        List<Map<String,Object>> items=new ArrayList<>();
        // Evidence is scoped to observed model, firmware and protocol, not a vendor-wide certification.
        Map<String,String> verified=Map.of(
            "dell-os9","2026-09-09 真机：S6100-ON / OS9 9.14(2.23)，SNMPv3 身份、服务标签、接口、环境传感器和机箱 MAC；SNMP 身份与接口基线。已验证拓扑关联采用 Cisco 侧 LLDP 观测；其他机型/固件待验收",
            "cisco-ios-xe","2026-09-09 真机：ASR1002-X / IOS XE 17.9.8，SNMPv3 身份、接口、110 项环境传感器与 LLDP；SNMP 身份与接口基线。验收时 CDP 在该设备上关闭；其他机型/固件待验收",
            "huawei-imana","2026-09-09 真机 SNMPv3：RH2288H V2 / RH2288H V2-12L、iMana (U1029)7.38，私有 MIB 身份、活动固件、整机健康、功耗及传感器；CPU DTS 作为温差。离线 RH2288 V2 的 SNMP 待上线验收，其他机型/固件待验收",
            "inspur-bmc","2026-09-09 真机 Redfish：SA5212M5 / BMC 4.26.6，身份、健康、41 项传感器/组件、功耗与配置基线；保留旧格式/不可用读数标记。SNMP 及其他机型/固件待验收");
        for(String[] profile:List.of(new String[]{"dell-idrac","Dell","iDRAC","SNMP,REDFISH","服务器身份、标准健康与传感器；具体型号和 OEM 指标待真机验收"},new String[]{"dell-os9","Dell","Networking OS9","SNMP,SSH","标准 MIB 接口计数；固定 SSH 身份、ARP、LLDP 查询，强制 host-key 指纹"},new String[]{"huawei-ibmc","Huawei","iBMC","SNMP,REDFISH","标准 Redfish 与 SNMP 指纹；不同机型资源差异以实际返回为准"},new String[]{"huawei-imana","Huawei","iMana","SNMP,SSH","固定 SSH 交互查询身份、健康和有单位传感器；旧固件不假定 Redfish"},new String[]{"cisco-ios-xe","Cisco","IOS XE","SNMP,SSH","标准 MIB 与固定 SSH 身份、ARP、LLDP/CDP；SHA2 依赖固件版本"},new String[]{"inspur-bmc","Inspur","BMC","SNMP,REDFISH","标准 Redfish / SNMP；多代固件及企业 OID 待型号核实"},new String[]{"h3c-hdm","H3C","HDM","SNMP,REDFISH","HDM 标准资源及可识别的旧 Members 格式"},new String[]{"h3c-comware","H3C","Comware","SNMP","25506 企业根与标准接口；历史 2011.10 分支待核验"},new String[]{"generic","Generic","通用设备","SNMP,REDFISH","只按成功读取列能力；未知厂商保持 generic"})){
            items.add(Map.of("id",profile[0],"vendor",profile[1],"family",profile[2],"protocols",List.of(profile[3].split(",")),"implemented",true,"verification",verified.containsKey(profile[0])?"HARDWARE_VERIFIED_SCOPED":"SIMULATOR_TESTED_HARDWARE_PENDING","notes",verified.getOrDefault(profile[0],profile[4])));
        }
        return Map.of("items",items,"protocols",drivers.keySet().stream().sorted().toList(),"credentialStorageReady",vault.ready()&&store.connected(),"collectorEnabled",collectorEnabled);
    }
}
