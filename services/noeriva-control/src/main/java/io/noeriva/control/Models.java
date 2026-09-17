package io.noeriva.control;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Models {
    private Models() {}
    public record Device(String id, String name, String type, String siteId, String siteName,
                         String vendor, String model, String managementAddress, String health,
                         String availability, Instant lastSeen, long revision, List<String> capabilities) {}
    public record CreateDevice(@NotBlank @Size(max=120) String name,
        @Pattern(regexp="HOST|BMC|SWITCH|ROUTER|FIREWALL") @NotNull String type,
        @NotBlank @Size(max=64) String siteId, @Size(max=120) String vendor, @Size(max=120) String model,
        @NotBlank @Pattern(regexp="[a-zA-Z0-9.:_-]{1,253}") String managementAddress) {}
    public record Site(String id, String name, String timezone) {}
    public record SourceState(String sourceId, String kind, Instant observedAt, long sequence, String epoch,
                              String health, Map<String,Double> metrics, String freshness) {}
    public record DeviceSummary(Device device, List<SourceState> sources, long activeAlerts,
                                Instant asOf, String sourceFreshness, double coverage, int resolution,
                                String dataRevision, boolean provisional, List<String> qualityFlags) {}
    public record NetworkInterface(String id, String deviceId, String name, String macAddress,
                                   String speedBps, String adminStatus, String operStatus) {}
    public record RegisterInterface(@NotBlank @Size(max=120) String name,
                                    @Pattern(regexp="[0-9]{1,20}") @NotNull String speedBps,
                                    @Size(max=40) String macAddress) {}
    public record Alert(String id, String deviceId, String deviceName, String severity, String state,
                        String title, Instant openedAt, Instant acknowledgedAt, String acknowledgedBy, long revision) {}
    public record Acknowledge(@Min(1) long revision) {}
    public record Event(String id, String deviceId, String kind, String severity, String message,
                        Instant observedAt, Instant ingestedAt, String source, List<String> qualityFlags) {}
    public record Collector(String id, String name, String siteId, String status, Instant lastHeartbeat,
                            Instant lastSuccessfulCollection, long queueBytes, long queueLimitBytes,
                            Instant oldestQueuedAt, String version, List<String> capabilities, String source) {}
    public record TopologyEvidence(String protocol,String sourceDeviceId,String sourceInterface,String sourceRef,Instant observedAt,String detail) {}
    public record Node(String id,String name,String type,String health,boolean registered,String siteId,
                       String availability,String freshness,List<Integer> vlanIds,List<String> addresses,String mac,
                       String confidence,List<String> qualityFlags,List<TopologyEvidence> evidence) {
        public Node(String id,String name,String type,String health){this(id,name,type,health,true,null,"UNKNOWN","MISSING",List.of(),List.of(),null,"OBSERVED",List.of(),List.of());}
    }
    public record Edge(String id,String source,String target,String sourceInterface,String targetInterface,
                       String kind,String provenance,Instant observedAt,boolean inferred,String confidence,
                       List<Integer> vlanIds,List<String> qualityFlags,List<TopologyEvidence> evidence) {
        public Edge { confidence=confidence==null?(inferred?"UNCONFIRMED":"OBSERVED"):confidence;vlanIds=vlanIds==null?List.of():List.copyOf(vlanIds);qualityFlags=qualityFlags==null?List.of():List.copyOf(qualityFlags);evidence=evidence==null?List.of():List.copyOf(evidence); }
        public Edge(String id,String source,String target,String sourceInterface,String targetInterface,String kind,String provenance,Instant observedAt){this(id,source,target,sourceInterface,targetInterface,kind,provenance,observedAt,false,"OBSERVED",List.of(),List.of(),List.of());}
    }
    public record TopologyVlan(int vlanId,String name,int nodeCount,int edgeCount) {}
    public record Topology(List<Node> nodes,List<Edge> edges,Instant asOf,List<String> qualityFlags,String view,List<TopologyVlan> vlans) {
        public Topology(List<Node> nodes,List<Edge> edges,Instant asOf,List<String> qualityFlags){this(nodes,edges,asOf,qualityFlags,"PHYSICAL",List.of());}
    }
    public record Observation(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,128}") String id,
        @NotBlank @Size(max=64) String deviceId, @NotNull @Pattern(regexp="primary|host|bmc|network|system|ssh") String sourceId,
        @NotNull @Pattern(regexp="DeviceSummaryObserved|SourceHealthChanged") String kind,
        @NotBlank @Size(max=64) String epoch, @Min(0) @Max(Long.MAX_VALUE-1) long sequence, @NotNull Instant observedAt,
        @NotNull @Pattern(regexp="HEALTHY|WARNING|CRITICAL|UNKNOWN") String health,
        @NotNull @Size(max=16) Map<String,Double> metrics, @Size(max=1000) String message) {}
    public record IngestBatch(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,128}") String batchId,
                              @NotEmpty @Size(max=500) List<@NotNull @Valid Observation> events) {}
    public record AcceptedBatch(String batchId, String status, int accepted, Instant receivedAt) {}
    public record Page<T>(List<T> items, String nextCursor, Instant asOf, String source, String mode) {}
    public record Items<T>(List<T> items, Instant asOf) {}
    public record Overview(long devices, long critical, long warning, long healthy, long unknown,
                           long stale, long activeAlerts, long collectors, Instant asOf, String mode) {}
}
