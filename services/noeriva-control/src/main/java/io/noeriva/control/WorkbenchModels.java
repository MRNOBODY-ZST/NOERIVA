package io.noeriva.control;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** Typed control-plane workflows; none of these DTOs is executable device configuration. */
public final class WorkbenchModels {
    private WorkbenchModels() {}
    public record IncidentInput(@NotBlank @Size(max=200) String title,@NotBlank @Pattern(regexp="INFO|WARNING|CRITICAL") String severity,
        @NotBlank @Size(max=64) String deviceId,@Size(max=128) String alertId,@Size(max=120) String assignee,@Size(max=2000) String note) {}
    public record IncidentUpdate(@Min(1) long revision,@NotBlank @Size(max=200) String title,
        @NotBlank @Pattern(regexp="OPEN|INVESTIGATING|RESOLVED") String status,@Size(max=120) String assignee,@Size(max=2000) String note) {}
    public record Note(String id,String author,String text,Instant createdAt) {}
    public record Incident(String id,String title,String severity,String status,String deviceId,String alertId,String assignee,
        String createdBy,Instant createdAt,Instant updatedAt,long revision,List<Note> notes) {}
    public record IncidentSummary(String id,String title,String severity,String status,String deviceId,String alertId,String assignee,
        String createdBy,Instant createdAt,Instant updatedAt,long revision,int noteCount) {}
    public record EvidenceInput(@NotBlank @Size(max=64) String deviceId,@NotBlank @Size(max=200) String title,
        @NotBlank @Pattern(regexp="NOTE|EVENT|OBSERVATION|NAT|LEASE|CONFIGURATION|CHECK") String kind,
        @NotBlank @Size(max=120) String source,@NotNull Instant observedAt,@NotBlank @Size(max=32768) String content,
        @NotBlank @Pattern(regexp="MANUAL|SYNTHETIC") String provenance) {}
    public record Evidence(String id,String deviceId,String title,String kind,String source,Instant observedAt,String content,
        String provenance,String sha256,String integrity,String createdBy,Instant createdAt) {}
    public record EvidenceMetadata(String id,String deviceId,String title,String kind,String source,Instant observedAt,
        String provenance,String sha256,String integrity,String createdBy,Instant createdAt) {}
    public record Manifest(int version,String algorithm,String sha256,String integrity,boolean worm,Evidence evidence) {}
    public record ConfigurationInput(@NotBlank @Size(max=64) String deviceId,@NotBlank @Size(max=200) String title,
        @NotBlank @Size(max=120) String source,@NotNull Instant capturedAt,@NotBlank @Size(max=65536) String content,
        @NotBlank @Pattern(regexp="MANUAL|SYNTHETIC") String provenance) {}
    public record Snapshot(String id,String deviceId,String title,String source,Instant capturedAt,String content,String provenance,
        String sha256,int redactedLines,String createdBy,Instant createdAt) {}
    public record SnapshotMetadata(String id,String deviceId,String title,String source,Instant capturedAt,String provenance,
        String sha256,int redactedLines,String createdBy,Instant createdAt) {}
    public record DiffLine(String kind,Integer beforeLine,Integer afterLine,String text) {}
    public record Diff(String deviceId,String beforeId,String afterId,int added,int removed,int unchanged,List<DiffLine> lines,Instant asOf) {}
    public record CheckInput(@NotBlank @Size(max=64) String deviceId,@NotBlank @Size(max=200) String name,
        @NotBlank @Pattern(regexp="TCP|HTTP|HTTPS|DNS|TLS") String type,@NotBlank @Size(max=253) String target,
        @Min(30) @Max(86400) int intervalSeconds,boolean enabled,@NotBlank @Pattern(regexp="MANUAL|SYNTHETIC") String provenance) {}
    public record CheckUpdate(@Min(1) long revision,@NotBlank @Size(max=200) String name,@NotBlank @Size(max=253) String target,
        @Min(30) @Max(86400) int intervalSeconds,boolean enabled) {}
    public record Revision(@Min(1) long revision) {}
    public record Check(String id,String deviceId,String name,String type,String target,int intervalSeconds,boolean enabled,boolean archived,
        String provenance,String execution,long revision,String createdBy,Instant createdAt,Instant updatedAt,CheckResult lastResult) {}
    public record CheckResultInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,128}") String id,@NotBlank @Size(max=64) String checkId,
        @NotNull Instant observedAt,@NotBlank @Pattern(regexp="PASS|FAIL|UNKNOWN") String status,Double latencyMs,
        @Size(max=1000) String message,@NotBlank @Size(max=120) String source,@NotBlank @Pattern(regexp="MANUAL|SYNTHETIC") String provenance,@Min(1) Long definitionRevision) {}
    public record CheckResult(String id,String checkId,Instant observedAt,String status,Double latencyMs,String message,String source,String provenance,Instant receivedAt,long definitionRevision) {}
    public record NetworkInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,128}") String id,@NotBlank @Size(max=64) String deviceId,
        @NotBlank @Pattern(regexp="NAT|ADDRESS_LEASE") String kind,@NotBlank @Size(max=45) String privateIp,
        Integer privatePort,@Size(max=45) String publicIp,Integer publicPort,@Pattern(regexp="TCP|UDP") String protocol,
        @NotNull Instant validFrom,@NotNull Instant validTo,@NotBlank @Pattern(regexp="COMPLETE|SNAPSHOT_ONLY") String lifecycle,
        @Min(0) @Max(300000) int clockUncertaintyMs,@NotBlank @Size(max=120) String source,
        @NotBlank @Pattern(regexp="MANUAL|SYNTHETIC") String provenance) {}
    public record NetworkEvidence(String id,String deviceId,String siteId,String kind,String privateIp,Integer privatePort,String publicIp,Integer publicPort,
        String protocol,Instant validFrom,Instant validTo,String lifecycle,int clockUncertaintyMs,String source,String provenance,Instant receivedAt) {}
    public record InvestigationInput(@NotBlank @Size(max=45) String ip,@Min(1) @Max(65535) int port,
        @NotBlank @Pattern(regexp="TCP|UDP") String protocol,@NotNull Instant at,
        @NotBlank @Pattern(regexp="PUBLIC_TO_PRIVATE|PRIVATE_TO_PUBLIC") String direction) {}
    public record Candidate(NetworkEvidence nat,List<NetworkEvidence> leases,List<String> qualityFlags) {}
    public record Investigation(String id,InvestigationInput query,String status,List<Candidate> candidates,List<String> qualityFlags,Instant asOf,String mode) {}
    public record Audit(String id,String actor,String action,String resourceId,Instant createdAt) {}
}
