package io.noeriva.control;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import java.util.Map;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"NOERIVA_BOOTSTRAP_PASSWORD=noeriva-local-demo","NOERIVA_COLLECTOR_PASSWORD=noeriva-local-demo"})
@AutoConfigureWebTestClient
@ActiveProfiles("demo")
class DemoApiTest {
    @Autowired WebTestClient client;
    private WebTestClient admin() { return client.mutate().defaultHeaders(h -> h.setBasicAuth("admin", "noeriva-local-demo")).build(); }
    @Test void protectedApiNeedsCredentials() { client.get().uri("/api/v1/devices").exchange().expectStatus().isUnauthorized(); }
    @Test void shortLivedSessionWorksAndInvalidSessionIsRejected() {
        var session=admin().get().uri("/api/v1/session").exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        client.get().uri("/api/v1/devices").header("Authorization","Bearer "+session.get("accessToken")).exchange().expectStatus().isOk();
        client.get().uri("/api/v1/devices").header("Authorization","Bearer invalid").exchange().expectStatus().isUnauthorized();
    }
    @Test void inventoryIsBoundedAndHasSyntheticProvenance() {
        admin().get().uri("/api/v1/devices?limit=2").exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(2).jsonPath("$.mode").isEqualTo("DEMO").jsonPath("$.nextCursor").isNotEmpty();
        admin().get().uri("/api/v1/devices?limit=101").exchange().expectStatus().isBadRequest();
    }
    @Test void sourceFreshnessIsIndependentAndUnknownDeviceIs404() {
        admin().get().uri("/api/v1/devices/bmc-compute-07/summary").exchange().expectStatus().isOk().expectBody().jsonPath("$.sources[0].freshness").isEqualTo("STALE");
        admin().get().uri("/api/v1/devices/missing/summary").exchange().expectStatus().isNotFound();
    }
    @Test void viewerCannotAcknowledgeAndMutationNeedsExplicitHeader() {
        client.post().uri("/api/v1/alerts/alert-temperature/acknowledge").headers(h -> h.setBasicAuth("viewer", "noeriva-local-demo")).header("X-Noeriva-Request", "1").bodyValue(Map.of("revision",1)).exchange().expectStatus().isForbidden();
        admin().post().uri("/api/v1/alerts/alert-temperature/acknowledge").bodyValue(Map.of("revision",1)).exchange().expectStatus().isForbidden();
    }
    @Test void interfaceOwnershipCheckedBeforeHeatmapQuery() {
        admin().get().uri("/api/v1/devices/compute-07/interfaces/if-core-1/bandwidth/heatmap?timezone=UTC").exchange().expectStatus().isNotFound();
    }
    @Test void creatingDeviceReturnsCommittedUnknownState() {
        admin().post().uri("/api/v1/devices").header("X-Noeriva-Request", "1").bodyValue(Map.of("name","new-server","type","HOST","siteId","lab-a","vendor","Generic","model","Linux","managementAddress","192.0.2.99"))
            .exchange().expectStatus().isCreated().expectBody().jsonPath("$.health").isEqualTo("UNKNOWN").jsonPath("$.lastSeen").isEmpty();
    }
    @Test void credentialRoutesHaveStricterRolesThanSafeCollectionViews() {
        client.get().uri("/api/v1/devices/compute-07/connections").headers(h->h.setBasicAuth("viewer","noeriva-local-demo")).exchange().expectStatus().isForbidden();
        client.post().uri("/api/v1/devices/compute-07/connections/snmp/test").headers(h->h.setBasicAuth("viewer","noeriva-local-demo")).header("X-Noeriva-Request","1").bodyValue(Map.of("revision",1)).exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/devices/compute-07/collection").headers(h->h.setBasicAuth("viewer","noeriva-local-demo")).exchange().expectStatus().isOk().expectBody().jsonPath("$.items").isArray();
        admin().get().uri("/api/v1/devices/compute-07/connections").exchange().expectStatus().isOk().expectBody().jsonPath("$.items").isArray();
        admin().get().uri("/api/v1/device-support").exchange().expectStatus().isOk().expectBody().jsonPath("$.credentialStorageReady").isEqualTo(false);
    }
}
