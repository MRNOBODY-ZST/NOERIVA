package io.noeriva.control.applications;

import java.math.*;
import java.time.*;
import java.util.*;
import static io.noeriva.control.applications.ApplicationModels.*;

/** Server-side interval aggregation. Lifetime counters never participate in this query. */
final class ApplicationWindow {
    private ApplicationWindow(){}
    static int resolution(Instant from,Instant to){long seconds=Duration.between(from,to).getSeconds();return seconds<=3600?60:seconds<=21600?300:seconds<=86400?3600:86400;}
    static String sql(Integer index,String direction,String q){return """
        WITH raw AS (
          SELECT application,direction,interface_index,JSONExtractInt(payload,'protocolIndex') protocol_index,
            toUnixTimestamp64Milli(observed_at) observed_ms,
            JSONExtractFloat(payload,'intervalSeconds') seconds,
            JSONExtractFloat(payload,'derivedBps') rate,
            JSONHas(payload,'derivedBps') AND JSONExtractRaw(payload,'derivedBps')!='null' has_rate,
            JSONExtractString(payload,'intervalBytes') exact_bytes,
            JSONExtract(payload,'qualityFlags','Array(String)') quality,
            lagInFrame(toUnixTimestamp64Milli(observed_at),1,0) OVER
              (PARTITION BY interface_index,application,direction,JSONExtractInt(payload,'protocolIndex') ORDER BY observed_at,observation_id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) previous_ms
          FROM noeriva.application_observations FINAL
          WHERE organization_id={org:String} AND device_id={device:String}
            AND observed_at>fromUnixTimestamp64Milli({from:Int64})
            AND observed_at<=fromUnixTimestamp64Milli({scanTo:Int64})
        """+(index==null?"":" AND interface_index={index:UInt32}")+(direction.isEmpty()?"":" AND direction={direction:String}")+(q.isEmpty()?"":" AND positionCaseInsensitiveUTF8(application,{q:String})>0")+"""
        ), intervals AS (
          SELECT *,observed_ms-toInt64(round(if(isFinite(seconds) AND seconds>0 AND seconds<=10800,seconds,0)*1000)) begin_ms,
            has_rate AND isFinite(rate) AND isFinite(rate*seconds) AND rate>=0 AND seconds>0 AND seconds<=10800
              AND begin_ms>=previous_ms AND (exact_bytes='' OR (match(exact_bytes,'^[0-9]{1,20}$') AND ifNull(toDecimal256OrNull(exact_bytes,9)<=toDecimal256('18446744073709551615',9),false))) valid,
            if(exact_bytes='',toDecimal256(if(isFinite(rate*seconds),rate*seconds/8,0),9),ifNull(toDecimal256OrNull(exact_bytes,9),toDecimal256(0,9))) delta,
            greatest(begin_ms,{from:Int64}) clipped_begin,least(observed_ms,{to:Int64}) clipped_end
          FROM raw
        ), expanded AS (
          SELECT *,arrayJoin(arrayConcat([-1],
            if(valid AND clipped_begin<clipped_end,
              range(toInt64(intDiv(clipped_begin-{from:Int64},{step:Int64})),toInt64(intDiv(clipped_end-1-{from:Int64},{step:Int64}))+1),
              if(observed_ms<={to:Int64},[toInt64(greatest(0,intDiv(observed_ms-{from:Int64}-1,{step:Int64})))],[])))) bucket
          FROM intervals WHERE (valid AND clipped_begin<clipped_end) OR observed_ms<={to:Int64}
        ), pieces AS (
          SELECT *,if(bucket=-1,{from:Int64},{from:Int64}+bucket*{step:Int64}) bucket_begin,
            if(bucket=-1,{to:Int64},least({to:Int64},bucket_begin+{step:Int64})) bucket_end,
            if(valid,greatest(0,least(clipped_end,bucket_end)-greatest(clipped_begin,bucket_begin)),0) millis,
            if(millis>0,delta*toDecimal256(millis,0)/toDecimal256(greatest(1,observed_ms-begin_ms),0),toDecimal256(0,9)) piece_bytes,
            arrayConcat(quality,if(valid AND exact_bytes='',['HISTORICAL_RATE_ESTIMATE'],[]),
              if(millis>0 AND (greatest(clipped_begin,bucket_begin)!=begin_ms OR least(clipped_end,bucket_end)!=observed_ms),['ESTIMATED_BOUNDARY'],[]),
              if(begin_ms<previous_ms AND has_rate,['OVERLAPPING_INTERVAL_EXCLUDED'],[])) piece_flags
          FROM expanded
        ), streams AS (
          SELECT bucket,application,direction,interface_index,protocol_index,
            sum(piece_bytes) byte_sum,sum(millis)/1000.0 duration,
            min((bucket_end-bucket_begin)/1000.0) expected,
            count() samples,maxIf(observed_ms,millis>0) observed,
            arrayDistinct(arrayFlatten(groupArray(piece_flags))) flags
          FROM pieces GROUP BY bucket,application,direction,interface_index,protocol_index
        )
        SELECT if(bucket=-1,'item','trend') kind,bucket,if(bucket=-1,application,'') app,direction,
          arraySort(arrayDistinct(groupArray(interface_index))) indices,
          if(sum(duration)>0,toString(sum(byte_sum)),NULL) bytes,
          if(countIf(duration<=0)>0,NULL,sum(toFloat64(byte_sum)*8/nullIf(duration,0))) meanBps,
          least(1.0,min(duration/expected)) coverage,sum(samples) samples,
          nullIf(max(observed),0) observedMillis,
          arrayDistinct(arrayFlatten(groupArray(flags))) flags
        FROM streams GROUP BY bucket,app,direction
        ORDER BY bucket,app,direction LIMIT 4097 FORMAT JSONEachRow
        """;}
    record Row(String kind,long bucket,String app,String direction,List<Integer> indices,String bytes,Double meanBps,double coverage,long samples,Long observedMillis,List<String> flags){}
    static WindowSummary assemble(String device,Instant from,Instant to,int resolution,int limit,List<Row> rows,Instant now,int freshnessSeconds){
        if(rows.size()>4096)throw new IllegalArgumentException("Application aggregation exceeded its group budget");
        var items=new ArrayList<WindowItem>();var flags=new TreeSet<String>();var indices=new TreeSet<Integer>();
        long sampleRows=0;Instant observed=null;double coverage=1;
        var trendRows=new HashMap<Long,Map<String,Row>>();
        for(var row:rows){
            flags.addAll(row.flags());indices.addAll(row.indices());
            if(row.bucket()<0){
                var itemFlags=new TreeSet<>(row.flags());if(row.coverage()<.999999)itemFlags.add("PARTIAL_WINDOW");if(row.indices().size()>1)itemFlags.add("INTERFACE_OVERLAP_POSSIBLE");
                items.add(new WindowItem(row.app(),row.direction(),row.indices(),row.meanBps(),rounded(row.bytes()),row.coverage(),row.samples(),List.copyOf(itemFlags)));
                sampleRows+=row.samples();coverage=Math.min(coverage,row.coverage());
                if(row.observedMillis()!=null&&(observed==null||row.observedMillis()>observed.toEpochMilli()))observed=Instant.ofEpochMilli(row.observedMillis());
            }else trendRows.computeIfAbsent(row.bucket(),k->new HashMap<>()).put(row.direction(),row);
        }
        if(items.isEmpty())coverage=0;
        if(coverage<.999999)flags.add("PARTIAL_WINDOW");if(indices.size()>1)flags.add("INTERFACE_OVERLAP_POSSIBLE");
        flags.add("OBSERVED_INTERVAL_MEAN");
        var trend=new ArrayList<TrendPoint>();
        for(long i=0;from.plusSeconds(i*resolution).isBefore(to);i++){
            var pair=trendRows.getOrDefault(i,Map.of());var in=pair.get("IN");var out=pair.get("OUT");
            Instant at=from.plusSeconds(i*resolution),end=at.plusSeconds(resolution);if(end.isAfter(to))end=to;
            trend.add(new TrendPoint(at,end,in==null?null:in.meanBps(),out==null?null:out.meanBps(),in==null?null:rounded(in.bytes()),out==null?null:rounded(out.bytes()),pair.values().stream().mapToDouble(Row::coverage).min().orElse(0)));
        }
        items.sort(Comparator.comparing(WindowItem::derivedBps,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(WindowItem::application).thenComparing(WindowItem::direction));
        var totals=rows.stream().filter(row->row.bucket()<0).toList();
        String in=sumBytes(totals.stream().filter(row->row.direction().equals("IN")).toList()),out=sumBytes(totals.stream().filter(row->row.direction().equals("OUT")).toList());
        Double mean=items.isEmpty()||items.stream().anyMatch(i->i.derivedBps()==null)?null:items.stream().mapToDouble(WindowItem::derivedBps).sum();
        return new WindowSummary(device,now,observed,"CLICKHOUSE","CONNECTED",observed==null?"MISSING":observed.isBefore(now.minusSeconds(freshnessSeconds))?"STALE":"FRESH",
            "OBSERVED_INTERVAL_MEAN",from,to,List.copyOf(indices),sampleRows,items.size(),items.size()>limit,List.copyOf(flags),items.stream().limit(limit).toList(),sumBytes(totals),in,out,mean,coverage,resolution,List.copyOf(trend));
    }
    private static String rounded(String value){return value==null?null:new BigDecimal(value).setScale(0,RoundingMode.HALF_UP).toPlainString();}
    private static String sumBytes(List<Row> rows){var values=rows.stream().map(Row::bytes).filter(Objects::nonNull).toList();return values.isEmpty()?null:rounded(values.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString());}
}
