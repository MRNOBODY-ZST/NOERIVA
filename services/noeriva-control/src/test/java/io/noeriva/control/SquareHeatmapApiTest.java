package io.noeriva.control;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"NOERIVA_BOOTSTRAP_PASSWORD=noeriva-local-demo","NOERIVA_COLLECTOR_PASSWORD=noeriva-local-demo"})
@AutoConfigureWebTestClient @ActiveProfiles("demo")
class SquareHeatmapApiTest {
    @Autowired WebTestClient client;

    @Test void ownedDeviceAndInterfaceAcceptBothSquareSizesWithoutReplacingHourlyContract() {
        for(String path:new String[]{"/api/v1/devices/core-asr-01/bandwidth/heatmap","/api/v1/devices/core-asr-01/interfaces/if-core-1/bandwidth/heatmap"}) {
            for(int size:new int[]{7,28})client.get().uri(path+"?timezone=Asia/Shanghai&gridSize="+size)
                .headers(h->h.setBasicAuth("admin","noeriva-local-demo")).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.cells.length()").isEqualTo(168).jsonPath("$.matrix.size").isEqualTo(size)
                .jsonPath("$.matrix.cells.length()").isEqualTo(size*size).jsonPath("$.matrix.cells[0].index").isEqualTo(0)
                .jsonPath("$.matrix.cells[0].intervals[0].from").isNotEmpty();
            client.get().uri(path+"?timezone=UTC").headers(h->h.setBasicAuth("admin","noeriva-local-demo"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.matrix").isEmpty();
        }
    }

    @Test void unsupportedGridReturns400AndOwnershipStillProtectsData() {
        for(String path:new String[]{"/api/v1/devices/core-asr-01/bandwidth/heatmap","/api/v1/devices/core-asr-01/interfaces/if-core-1/bandwidth/heatmap"})
            client.get().uri(path+"?timezone=UTC&gridSize=14").headers(h->h.setBasicAuth("admin","noeriva-local-demo"))
                .exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/devices/compute-07/interfaces/if-core-1/bandwidth/heatmap?timezone=UTC&gridSize=28")
            .headers(h->h.setBasicAuth("admin","noeriva-local-demo")).exchange().expectStatus().isNotFound();
    }
}
