package io.noeriva.control.devices;

import io.noeriva.control.Models.Device;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class DeviceAccessModels {
    private DeviceAccessModels(){}
    public record Management(Device device,long inventoryRevision){}
    public record Update(@Min(1) long revision,@NotBlank @Size(max=120) String name,
        @NotBlank @Pattern(regexp="HOST|BMC|SWITCH|ROUTER|FIREWALL") String type,@NotBlank @Size(max=64) String siteId,
        @Size(max=120) String vendor,@Size(max=120) String model,
        @NotBlank @Pattern(regexp="[a-zA-Z0-9.:_-]{1,253}") String managementAddress){}
    public record Revision(@Min(1) long revision){}
    public record State(@Min(1) long revision,boolean enabled){}
    public record Save(@Min(0) long revision,@NotBlank @Size(max=253) String host,@Min(1) @Max(65535) int port,
        boolean enabled,@Min(15) @Max(86400) int intervalSeconds,@Min(250) @Max(10000) int timeoutMillis,
        @Min(1) @Max(256) int maxInterfaces,@Size(max=120) String username,@Size(max=10) String snmpVersion,
        @Size(max=20) String securityLevel,@Size(max=20) String authProtocol,@Size(max=20) String privacyProtocol,
        @Size(max=64) String contextName,@Size(max=10) String tlsMode,@Size(max=95) String certificateSha256,
        @Valid SecretInput secrets,@Size(max=32) String sshProfile,@Size(max=60) String sshHostKeySha256){
        public Save(long revision,String host,int port,boolean enabled,int intervalSeconds,int timeoutMillis,int maxInterfaces,
                    String username,String snmpVersion,String securityLevel,String authProtocol,String privacyProtocol,
                    String contextName,String tlsMode,String certificateSha256,SecretInput secrets){
            this(revision,host,port,enabled,intervalSeconds,timeoutMillis,maxInterfaces,username,snmpVersion,securityLevel,
                 authProtocol,privacyProtocol,contextName,tlsMode,certificateSha256,secrets,null,null);
        }
        @Override public String toString(){return "Save[REDACTED]";}
    }
    public record SecretInput(@Size(max=1024) String community,@Size(max=1024) String authPassword,
        @Size(max=1024) String privacyPassword,@Size(max=1024) String password){
        @Override public String toString(){return "SecretInput[REDACTED]";}
    }
    public record Settings(String host,int port,int intervalSeconds,int timeoutMillis,int maxInterfaces,String username,
        String snmpVersion,String securityLevel,String authProtocol,String privacyProtocol,String contextName,String tlsMode,
        String certificateSha256,boolean hasCommunity,boolean hasAuthPassword,boolean hasPrivacyPassword,boolean hasPassword,
        String sshProfile,String sshHostKeySha256){
        public Settings(String host,int port,int intervalSeconds,int timeoutMillis,int maxInterfaces,String username,
            String snmpVersion,String securityLevel,String authProtocol,String privacyProtocol,String contextName,String tlsMode,
            String certificateSha256,boolean hasCommunity,boolean hasAuthPassword,boolean hasPrivacyPassword,boolean hasPassword){
            this(host,port,intervalSeconds,timeoutMillis,maxInterfaces,username,snmpVersion,securityLevel,authProtocol,
                privacyProtocol,contextName,tlsMode,certificateSha256,hasCommunity,hasAuthPassword,hasPrivacyPassword,hasPassword,null,null);
        }
        public DeviceProtocol.Target target(String protocol,String address){return new DeviceProtocol.Target(host,address,port,protocol,username,snmpVersion,securityLevel,authProtocol,privacyProtocol,contextName,tlsMode,certificateSha256,timeoutMillis,maxInterfaces,sshProfile,sshHostKeySha256);}
    }
    public record Stored(String org,String device,String slot,long revision,boolean enabled,Settings settings,String ciphertext,
        String status,Instant lastAttemptAt,Instant lastSuccessAt,Instant nextPollAt,String errorCode,String errorMessage,
        DeviceProtocol.Reading lastReading,DeviceProtocol.Reading lastPublished,String lease,Instant leaseUntil,long sequence,String sourceEpoch){
        @Override public String toString(){return "Stored["+slot+", revision="+revision+", status="+status+"]";}
        public String protocol(){return switch(slot){case "snmp"->"SNMP";case "redfish"->"REDFISH";case "ssh"->"SSH";default->"UNKNOWN";};}
        public String scope(){return org+"/"+device+"/"+slot;}
    }
    public record ConnectionView(String slot,String protocol,long revision,String host,int port,boolean enabled,
        int intervalSeconds,int timeoutMillis,int maxInterfaces,String username,String snmpVersion,String securityLevel,
        String authProtocol,String privacyProtocol,String contextName,String tlsMode,String certificateSha256,
        boolean hasCommunity,boolean hasAuthPassword,boolean hasPrivacyPassword,boolean hasPassword,
        String status,Instant lastAttemptAt,Instant lastSuccessAt,Instant nextPollAt,String errorCode,String errorMessage,DeviceProtocol.Reading lastReading,String sshProfile,String sshHostKeySha256){}
    public record CollectionView(String slot,String protocol,long revision,boolean enabled,String status,Instant lastAttemptAt,
        Instant lastSuccessAt,Instant nextPollAt,String errorCode,String errorMessage,DeviceProtocol.Reading lastReading){}
    public static ConnectionView view(Stored v){var s=v.settings();return new ConnectionView(v.slot(),v.protocol(),v.revision(),s.host(),s.port(),v.enabled(),s.intervalSeconds(),s.timeoutMillis(),s.maxInterfaces(),s.username(),s.snmpVersion(),s.securityLevel(),s.authProtocol(),s.privacyProtocol(),s.contextName(),s.tlsMode(),s.certificateSha256(),s.hasCommunity(),s.hasAuthPassword(),s.hasPrivacyPassword(),s.hasPassword(),v.status(),v.lastAttemptAt(),v.lastSuccessAt(),v.nextPollAt(),v.errorCode(),v.errorMessage(),v.lastReading(),s.sshProfile(),s.sshHostKeySha256());}
    public static CollectionView collection(Stored v){return new CollectionView(v.slot(),v.protocol(),v.revision(),v.enabled(),v.status(),v.lastAttemptAt(),v.lastSuccessAt(),v.nextPollAt(),v.errorCode(),v.errorMessage(),v.lastReading());}
}
