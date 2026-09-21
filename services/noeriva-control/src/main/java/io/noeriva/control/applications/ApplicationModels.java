package io.noeriva.control.applications;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ApplicationModels {
    private ApplicationModels(){}
    public record SettingsInput(@Min(0) long revision,boolean enabled,@Min(30) @Max(3600) int intervalSeconds,
        @NotEmpty @Size(max=8) List<@NotNull @Min(1) Integer> interfaceIndices,@Min(1) @Max(256) int maxRows){}
    public record StateInput(@Min(1) long revision,boolean enabled){}
    public record CollectInput(@Min(1) long revision){}
    public record Source(String deviceId,String deviceName,String siteId,long revision,boolean enabled,int intervalSeconds,
        List<Integer> interfaceIndices,int maxRows,String status,Instant lastAttemptAt,Instant lastSuccessAt,Instant nextPollAt,
        String errorCode,String errorMessage,long credentialRevision,String protocol,int lastRowCount,List<String> qualityFlags){}
    public record Observation(String id,String deviceId,int interfaceIndex,String interfaceName,int protocolIndex,
        String application,String direction,Instant observedAt,String bytes,String packets,Double reportedBps,
        Double derivedBps,Double derivedPacketsPerSecond,Double intervalSeconds,String sourceEpoch,List<String> qualityFlags,String intervalBytes){
        public Observation(String id,String deviceId,int interfaceIndex,String interfaceName,int protocolIndex,String application,String direction,Instant observedAt,String bytes,String packets,Double reportedBps,Double derivedBps,Double derivedPacketsPerSecond,Double intervalSeconds,String sourceEpoch,List<String> qualityFlags){this(id,deviceId,interfaceIndex,interfaceName,protocolIndex,application,direction,observedAt,bytes,packets,reportedBps,derivedBps,derivedPacketsPerSecond,intervalSeconds,sourceEpoch,qualityFlags,null);}
    }
    public record WindowItem(String application,String direction,List<Integer> interfaceIndices,Double derivedBps,
        String cumulativeBytes,double coverage,long observationCount,List<String> qualityFlags){}
    public record TrendPoint(Instant timestamp,Instant end,Double inBps,Double outBps,String inBytes,String outBytes,double coverage){}
    public record WindowSummary(String deviceId,Instant asOf,Instant observedAt,String source,String mode,String freshness,
        String rateBasis,Instant from,Instant to,List<Integer> interfaceIndices,long sampleRows,int totalApplications,
        boolean truncated,List<String> qualityFlags,List<WindowItem> items,String totalBytes,String inBytes,String outBytes,
        Double meanBps,double coverage,int resolutionSeconds,List<TrendPoint> trend){}
    public record SummaryItem(String application,String direction,List<Integer> interfaceIndices,Double derivedBps,
        Double reportedBps,int observationCount,List<String> qualityFlags){}
    public record Summary(String deviceId,Instant asOf,Instant observedAt,String source,String mode,String freshness,
        String rateBasis,List<Integer> interfaceIndices,int sampleRows,int totalApplications,boolean truncated,
        List<String> qualityFlags,List<SummaryItem> items){}
    /** Driver-only structures; no credentials and no inferred rate. */
    public record CounterRow(int interfaceIndex,String interfaceName,String interfaceIdentity,int protocolIndex,
        String application,String enableTime,String inBytes,String outBytes,String inPackets,String outPackets,
        Double reportedInBps,Double reportedOutBps,List<String> qualityFlags){}
    public record Sample(Instant observedAt,String sysUptimeTicks,String engineId,String engineBoots,
        List<CounterRow> rows,List<String> qualityFlags){}
    public record Stored(String organizationId,String deviceId,long revision,boolean enabled,int intervalSeconds,
        List<Integer> interfaceIndices,int maxRows,String status,Instant lastAttemptAt,Instant lastSuccessAt,Instant nextPollAt,
        String errorCode,String errorMessage,String leaseToken,Sample baseline,long baselineCredentialRevision,
        int lastRowCount,List<String> qualityFlags){}
}
