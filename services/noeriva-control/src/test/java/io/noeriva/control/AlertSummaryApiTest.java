package io.noeriva.control;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"NOERIVA_BOOTSTRAP_PASSWORD=noeriva-local-demo","NOERIVA_COLLECTOR_PASSWORD=noeriva-local-demo"})
@AutoConfigureWebTestClient @ActiveProfiles("demo")
class AlertSummaryApiTest {
    @Autowired WebTestClient client;
    private WebTestClient viewer(){return client.mutate().defaultHeaders(h->h.setBasicAuth("viewer","noeriva-local-demo")).build();}
    @Test void summaryCountsTheWholeQueueAndRequiresAuthentication(){
        client.get().uri("/api/v1/alerts/summary").exchange().expectStatus().isUnauthorized();
        var alerts=viewer().get().uri("/api/v1/alerts?limit=100").exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        var rows=(java.util.List<Map<String,Object>>)alerts.get("items");
        var summary=viewer().get().uri("/api/v1/alerts/summary").exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertThat(((Number)summary.get("open")).longValue()).isEqualTo(rows.stream().filter(a->a.get("state").equals("OPEN")).count());
        assertThat(((Number)summary.get("active")).longValue()).isEqualTo(rows.stream().filter(a->!a.get("state").equals("RESOLVED")).count());
    }
    @Test void unknownDeviceIsNotAnUnscopedCountAndEmptyDeviceIsZero(){
        viewer().get().uri("/api/v1/alerts/summary?deviceId=missing").exchange().expectStatus().isNotFound();
        viewer().get().uri("/api/v1/alerts/summary?deviceId=core-asr-01").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.open").isEqualTo(0).jsonPath("$.acknowledged").isEqualTo(0).jsonPath("$.active").isEqualTo(0);
        viewer().get().uri("/api/v1/alerts/summary?deviceId="+"a".repeat(65)).exchange().expectStatus().isBadRequest();
    }
}
