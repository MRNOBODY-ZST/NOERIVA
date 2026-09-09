package io.noeriva.control;

import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static io.noeriva.control.Models.*;
import static io.noeriva.control.WorkspaceModels.*;

interface WorkspaceReadRepository {
    Flux<MonitoringSource> monitoring(String org,int limit,SourceCursor cursor,String q,String site,String device,String freshness,Instant asOf);
    Flux<WorkspaceInterface> interfaces(String org,int limit,String cursor,String q,String site,String device,Instant asOf);
    Flux<SiteHealth> siteHealth(String org,String site,Instant asOf);
    Flux<Device> priorityDevices(String org,String site,int limit);
    Mono<MonitoringSource> trafficSource(String org,String site,Instant asOf);
    Mono<Overview> totals(String org,String site,Instant asOf);
}
