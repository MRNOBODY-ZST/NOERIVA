package io.noeriva.control.discovery;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.*;

public final class DiscoveryModels {
    private DiscoveryModels() {}
    public record RunInput(@NotEmpty @Size(max=8) List<@NotBlank @Size(max=64) String> sourceDeviceIds,
                           @NotBlank @Size(max=32) String cidr,@NotBlank @Size(max=64) String siteId) {}
    public record RegisterInput(@Min(1) long revision,@NotBlank @Size(max=120) String name,
                                @NotBlank @Pattern(regexp="HOST|BMC|SWITCH|ROUTER|FIREWALL") String type) {}
    public record LinkInput(@Min(1) long revision,@NotBlank @Size(max=64) String deviceId) {}
    public record RunResult(String id,String siteId,String cidr,Instant asOf,int sourcesRequested,int sourcesUsed,
                            int observationsRead,int candidatesUpdated,int existingCount,int duplicateCount,int conflictCount,
                            List<SourceResult> sources,List<String> qualityFlags) {}
    public record SourceResult(String deviceId,String deviceName,String status,Instant observedAt,int acceptedCount,String reason) {}
    public record Candidate(String id,long revision,String address,String siteId,String name,String mac,String status,
                            List<String> reasons,List<Evidence> evidence,String associatedDeviceId,Instant firstSeenAt,Instant lastSeenAt) {}
    public record Evidence(String sourceDeviceId,String sourceDeviceName,String source,Instant observedAt,String address,
                           String mac,String interfaceName,String vlan,String name,String chassisId,String chassisSubtype,String portId,
                           Long ttlSeconds,Double ageMinutes,Instant validUntil,List<String> qualityFlags) {}
}
