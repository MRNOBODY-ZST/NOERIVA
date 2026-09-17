package io.noeriva.control.nat;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class NatModels {
 private NatModels(){}
 public record SettingsInput(@Min(0) long revision,boolean enabled,@Size(max=45) String sourceAddress){}
 public record StateInput(@Min(1) long revision,boolean enabled){}
 public record SourceView(String deviceId,String deviceName,String siteId,String sourceAddress,long revision,boolean enabled,String status,
  Instant lastPacketAt,Instant lastEventAt,Instant lastPersistedAt,String lastError,long templates,long received,long accepted,long persisted,long dropped,long sequenceGaps,long unknownTemplates,long parseErrors,long duplicatePackets,long restartCount,Instant updatedAt,List<String> qualityFlags){}
 public record Binding(String organizationId,String deviceId,String siteId,String sourceAddress,long revision){}
 public record NatEvent(String id,String deviceId,String siteId,String sourceAddress,long sourceDomain,String exporterEpoch,long packetSequence,int templateId,String templateSha256,String packetSha256,int recordIndex,
  String eventType,Integer protocol,Long vrfId,String privateIp,Integer privatePort,String publicIp,Integer publicPort,String destinationIp,Integer destinationPort,String translatedDestinationIp,Integer translatedDestinationPort,Long poolId,
  Instant deviceEventAt,Instant exportedAt,Instant receivedAt,List<String> qualityFlags,String provenance){}
 public record Decoded(List<NatEvent> events,int templates,long unknownTemplates,boolean sequenceGap,boolean duplicate,boolean restart,List<String> qualityFlags){}
 public record Envelope(String organizationId,String deviceId,List<NatEvent> events){}
 public record EventPage(List<NatEvent> items,String nextCursor,Instant asOf,String mode){}
 public record SourceItems(List<SourceView> items){}
 public static final List<String> BASE_FLAGS=List.of("UDP_UNAUTHENTICATED","COMPLETENESS_NOT_GUARANTEED");
}
