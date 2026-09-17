package io.noeriva.control;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"NOERIVA_BOOTSTRAP_PASSWORD=noeriva-local-demo","NOERIVA_COLLECTOR_PASSWORD=noeriva-local-demo"})
@AutoConfigureWebTestClient
@ActiveProfiles("demo")
class RollupJobsApiTest {
    @Autowired WebTestClient client;
    @Test void demoCannotPretendToQueueDurableHistoricalWork() {
        client.post().uri("/api/v1/query-jobs").headers(h->h.setBasicAuth("admin","noeriva-local-demo")).header("X-Noeriva-Request","1")
            .bodyValue(Map.of("deviceId","core-asr-01","interfaceId","if-core-1","from","2026-09-01T00:00:00Z","to","2026-09-01T01:00:00Z","direction","rx"))
            .exchange().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("REPAIR_JOBS_UNAVAILABLE");
    }
    @Test void viewerCannotStartRepairAndUnauthenticatedStatusReadIsRejected() {
        client.post().uri("/api/v1/query-jobs").headers(h->h.setBasicAuth("viewer","noeriva-local-demo")).header("X-Noeriva-Request","1")
            .bodyValue(Map.of("deviceId","core-asr-01","interfaceId","if-core-1","from","2026-09-01T00:00:00Z","to","2026-09-01T01:00:00Z","direction","rx"))
            .exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/query-jobs/missing").exchange().expectStatus().isUnauthorized();
    }
}
