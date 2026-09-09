package io.noeriva.control;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import static io.noeriva.control.Models.*;

/** Bounded read models for the Clarity workspace; no telemetry samples are persisted here. */
public final class WorkspaceModels {
    private WorkspaceModels() {}
    public record MonitoringSource(String deviceId,String deviceName,String deviceType,String siteId,String siteName,
        String sourceId,String kind,String health,Instant observedAt,String freshness,Map<String,Double> metrics,long sequence,String epoch) {}
    public record WorkspaceInterface(String id,String deviceId,String deviceName,String siteId,String siteName,String name,
        String macAddress,String speedBps,String adminStatus,String operStatus,Instant deviceLastSeen,String deviceFreshness) {}
    public record SiteHealth(String siteId,String siteName,String timezone,long devices,long critical,long warning,long healthy,long unknown,long stale) {}
    public record WorkspaceOverview(Overview totals,List<SiteHealth> siteHealth,List<Device> priorityDevices,MonitoringSource trafficSource,List<Event> recentEvents,
        String recentEventsStatus,Instant asOf,String mode,List<String> qualityFlags) {}
    public record WorkspaceSearch(List<Device> assets,List<Event> recentEvents,String query,int eventScanLimit,Instant eventWindowFrom,
        Instant eventWindowTo,String eventScope,String recentEventsStatus,Instant asOf,String mode,List<String> qualityFlags,
        List<WorkspaceInterface> interfaces,String provider,String status,Instant indexedAt) {
        public WorkspaceSearch(List<Device> assets,List<Event> recentEvents,String query,int eventScanLimit,Instant eventWindowFrom,Instant eventWindowTo,String eventScope,String recentEventsStatus,Instant asOf,String mode,List<String> qualityFlags){this(assets,recentEvents,query,eventScanLimit,eventWindowFrom,eventWindowTo,eventScope,recentEventsStatus,asOf,mode,qualityFlags,List.of(),"SIMULATED","READY",asOf);}
    }
    record HistoryResult(List<Event> items,String status) {}
    record SourceCursor(String deviceId,String sourceId) implements Comparable<SourceCursor> {
        @Override public int compareTo(SourceCursor other) {
            int devices=deviceId.compareTo(other.deviceId);return devices!=0?devices:sourceId.compareTo(other.sourceId);
        }
        static SourceCursor decode(String value) {
            if(value.isEmpty()) return new SourceCursor("","");
            try {
                String decoded=new String(java.util.Base64.getUrlDecoder().decode(value),java.nio.charset.StandardCharsets.UTF_8);
                String[] fields=decoded.split("\\n",-1);
                if(fields.length!=2||fields[0].isEmpty()||fields[0].length()>64||!fields[1].matches("primary|host|bmc|network|system|ssh"))throw new IllegalArgumentException();
                return new SourceCursor(fields[0],fields[1]);
            }catch(IllegalArgumentException ex){throw new IllegalArgumentException("Invalid source cursor");}
        }
        static String encode(MonitoringSource source) {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((source.deviceId()+"\n"+source.sourceId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
