package io.noeriva.control;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"noeriva.workspace-test=true","NOERIVA_BOOTSTRAP_PASSWORD=noeriva-local-demo","NOERIVA_COLLECTOR_PASSWORD=noeriva-local-demo"})
@AutoConfigureWebTestClient @ActiveProfiles("demo")
class WorkspaceApiTest {
    @Autowired WebTestClient client;
    private WebTestClient viewer(){return client.mutate().defaultHeaders(h->h.setBasicAuth("viewer","noeriva-local-demo")).build();}
    @Test void monitoringPagesSourcesWithoutLosingIndependentFreshness(){
        var first=viewer().get().uri("/api/v1/workspace/monitoring?limit=1").exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat((java.util.List<?>)first.get("items")).hasSize(1);
        assertThat(first.get("mode")).isEqualTo("DEMO");
        String cursor=(String)first.get("nextCursor");assertThat(cursor).isNotBlank();
        viewer().get().uri(b->b.path("/api/v1/workspace/monitoring").queryParam("limit",1).queryParam("cursor",cursor).build())
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.items[0].deviceId").isEqualTo("compute-07");
        viewer().get().uri("/api/v1/workspace/monitoring?freshness=STALE").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.items[0].deviceId").isEqualTo("bmc-compute-07").jsonPath("$.items[0].metrics.temperature_celsius").isEqualTo(72.0)
            .jsonPath("$.items[0].freshness").isEqualTo("STALE");
    }
    @Test void monitoringFiltersAndRejectsUnboundedOrMalformedQueries(){
        viewer().get().uri("/api/v1/workspace/monitoring?deviceId=compute-07&siteId=lab-a&q=comp").exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(1);
        viewer().get().uri("/api/v1/workspace/monitoring?siteId=edge-b").exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(0);
        viewer().get().uri("/api/v1/workspace/monitoring?limit=101").exchange().expectStatus().isBadRequest();
        viewer().get().uri("/api/v1/workspace/monitoring?cursor=not-a-cursor").exchange().expectStatus().isBadRequest();
        viewer().get().uri("/api/v1/workspace/monitoring?freshness=ONLINE").exchange().expectStatus().isBadRequest();
    }
    @Test void interfaceDirectoryIncludesDeviceScopeAndNeverInfersInterfaceFreshness(){
        viewer().get().uri("/api/v1/workspace/interfaces?deviceId=compute-07&q=eno").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.items.length()").isEqualTo(1).jsonPath("$.items[0].name").isEqualTo("eno1")
            .jsonPath("$.items[0].deviceName").isEqualTo("compute-07").jsonPath("$.items[0].siteId").isEqualTo("lab-a")
            .jsonPath("$.items[0].deviceFreshness").isEqualTo("FRESH").jsonPath("$.items[0].speedBps").isEqualTo("10000000000");
        viewer().get().uri("/api/v1/workspace/interfaces?siteId=edge-b").exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(0);
        viewer().get().uri("/api/v1/workspace/interfaces?limit=1").exchange().expectStatus().isOk().expectBody().jsonPath("$.nextCursor").isNotEmpty();
    }
    @Test void overviewUsesExactSiteCountsAndMakesHistoryScopeExplicit(){
        viewer().get().uri("/api/v1/workspace/overview").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.totals.devices").isEqualTo(5).jsonPath("$.totals.warning").isEqualTo(1)
            .jsonPath("$.totals.unknown").isEqualTo(1).jsonPath("$.totals.stale").isEqualTo(1)
            .jsonPath("$.siteHealth.length()").isEqualTo(2).jsonPath("$.priorityDevices.length()").isEqualTo(2)
            .jsonPath("$.recentEventsStatus").isEqualTo("AVAILABLE").jsonPath("$.recentEvents.length()").isEqualTo(5)
            .jsonPath("$.trafficSource.deviceName").isEqualTo("core-asr-01");
        viewer().get().uri("/api/v1/workspace/overview?siteId=edge-b").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.totals.devices").isEqualTo(0).jsonPath("$.recentEvents.length()").isEqualTo(0).jsonPath("$.trafficSource").isEmpty()
            .jsonPath("$.recentEventsStatus").isEqualTo("NOT_REQUESTED");
    }
    @Test void searchProvidesBoundedResultsAndDeclaresRecentEventCoverage(){
        viewer().get().uri("/api/v1/workspace/search?q=compute&limit=1").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.assets.length()").isEqualTo(1).jsonPath("$.assets[0].name").isEqualTo("compute-07").jsonPath("$.recentEvents.length()").isEqualTo(1)
            .jsonPath("$.eventScope").isEqualTo("RECENT_7_DAYS_MAX_100").jsonPath("$.eventScanLimit").isEqualTo(100);
        viewer().get().uri("/api/v1/workspace/search?q=x").exchange().expectStatus().isBadRequest();
        viewer().get().uri("/api/v1/workspace/search?q=compute&limit=21").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/workspace/overview").exchange().expectStatus().isUnauthorized();
    }
}
