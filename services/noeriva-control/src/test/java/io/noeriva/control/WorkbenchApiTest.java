package io.noeriva.control;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"NOERIVA_BOOTSTRAP_PASSWORD=noeriva-local-demo","NOERIVA_COLLECTOR_PASSWORD=noeriva-local-demo"})
@AutoConfigureWebTestClient @ActiveProfiles("demo")
class WorkbenchApiTest {
    @Autowired WebTestClient client;
    WebTestClient.RequestBodySpec post(String path){return client.post().uri("/api/v1/workbench"+path).headers(h->h.setBasicAuth("admin","noeriva-local-demo")).header("X-Noeriva-Request","1");}
    WebTestClient.RequestHeadersSpec<?> get(String path){return client.get().uri("/api/v1/workbench"+path).headers(h->h.setBasicAuth("admin","noeriva-local-demo"));}
    @SuppressWarnings("unchecked") Map<String,Object> created(String path,Object body){return post(path).bodyValue(body).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();}

    @Test void incidentPersistsNotesAndRejectsStaleRevision() {
        var incident=created("/incidents",Map.of("title","温度调查","severity","WARNING","deviceId","bmc-compute-07","note","先检查温度传感器"));
        String id=incident.get("id").toString();
        var update=Map.of("revision",1,"title","温度调查进行中","status","INVESTIGATING","note","已联系现场");
        post("/incidents/"+id+"/updates").bodyValue(update).exchange().expectStatus().isOk().expectBody().jsonPath("$.revision").isEqualTo(2).jsonPath("$.notes.length()").isEqualTo(2);
        post("/incidents/"+id+"/updates").bodyValue(update).exchange().expectStatus().isEqualTo(409);
        get("/incidents/"+id).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("INVESTIGATING");
    }
    @Test void evidenceChecksumIsIndependentlyVerifiableAndReadingIsAudited() throws Exception {
        String content="带原始换行的证据\n第二行";
        var evidence=created("/evidence",Map.of("deviceId","compute-07","title","现场备注","kind","NOTE","source","test-fixture","observedAt",Instant.now().toString(),"content",content,"provenance","SYNTHETIC"));
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(evidence.get("sha256")).isEqualTo(digest);
        get("/evidence/"+evidence.get("id")+"/manifest").exchange().expectStatus().isOk().expectBody().jsonPath("$.sha256").isEqualTo(digest).jsonPath("$.worm").isEqualTo(false);
        get("/audit?resourceId="+evidence.get("id")).exchange().expectStatus().isOk().expectBody().jsonPath("$.items[0].action").isEqualTo("EVIDENCE_EXPORTED");
    }
    @Test void configurationRedactsSecretsAndReturnsActualLineDiff() {
        var common=new HashMap<String,Object>(Map.of("deviceId","lab-sw-01","title","配置快照","source","manual-fixture","capturedAt",Instant.now().toString(),"provenance","SYNTHETIC"));
        common.put("content","hostname lab-sw-01\nusername audit secret never-store-me\nlogging trap informational");
        var before=created("/configuration/snapshots",common);
        assertThat(before.get("content").toString()).doesNotContain("never-store-me").contains("[REDACTED]");
        common.put("content","hostname lab-sw-01\nusername audit secret other-secret\nlogging trap warning");
        var after=created("/configuration/snapshots",common);
        get("/configuration/diff?before="+before.get("id")+"&after="+after.get("id")).exchange().expectStatus().isOk().expectBody().jsonPath("$.added").isEqualTo(1).jsonPath("$.removed").isEqualTo(1);
    }
    @Test void checksRemainUnknownUntilAResultAndDuplicateResultCannotOverwrite() {
        var check=created("/checks",Map.of("deviceId","lab-sw-01","name","SSH入口","type","TCP","target","192.0.2.2:22","intervalSeconds",60,"enabled",true,"provenance","SYNTHETIC"));
        assertThat(check.get("lastResult")).isNull();
        var result=new HashMap<String,Object>(Map.of("id","result-"+UUID.randomUUID(),"checkId",check.get("id"),"observedAt",Instant.now().toString(),"status","PASS","latencyMs",12.5,"message","fixture only","source","fixture-collector","provenance","SYNTHETIC"));
        created("/check-results",result); created("/check-results",result);
        result.put("status","FAIL");post("/check-results").bodyValue(result).exchange().expectStatus().isEqualTo(409);
        get("/checks/"+check.get("id")).exchange().expectStatus().isOk().expectBody().jsonPath("$.lastResult.status").isEqualTo("PASS");
    }
    @Test void investigationUsesIntervalsAndNeverInventsMissingLease() {
        String suffix=UUID.randomUUID().toString();
        Instant at=Instant.now().minusSeconds(60);
        var nat=new HashMap<String,Object>(Map.of("id","nat-"+suffix,"deviceId","compute-07","kind","NAT","privateIp","192.0.2.7","privatePort",51000,"publicIp","198.51.100.26","publicPort",54021,"protocol","TCP","validFrom",at.minusSeconds(10).toString(),"validTo",at.plusSeconds(10).toString()));
        nat.putAll(Map.of("lifecycle","COMPLETE","clockUncertaintyMs",0,"source","test-fixture","provenance","SYNTHETIC"));
        created("/network-evidence",nat);
        var query=Map.of("ip","198.51.100.26","port",54021,"protocol","TCP","at",at.toString(),"direction","PUBLIC_TO_PRIVATE");
        post("/investigations").bodyValue(query).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("INSUFFICIENT_EVIDENCE");
        var lease=new HashMap<String,Object>(Map.of("id","lease-"+suffix,"deviceId","compute-07","kind","ADDRESS_LEASE","privateIp","192.0.2.7","validFrom",at.minusSeconds(30).toString(),"validTo",at.plusSeconds(30).toString(),"lifecycle","COMPLETE","clockUncertaintyMs",0,"source","test-fixture","provenance","SYNTHETIC"));
        created("/network-evidence",lease);
        post("/investigations").bodyValue(query).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("CONFIRMED").jsonPath("$.candidates[0].nat.deviceId").isEqualTo("compute-07");
    }
    @Test void viewerCannotMutateAndReferencesAndQueryBoundsAreValidated() {
        client.post().uri("/api/v1/workbench/incidents").headers(h->h.setBasicAuth("viewer","noeriva-local-demo")).header("X-Noeriva-Request","1")
            .bodyValue(Map.of("title","denied","severity","INFO","deviceId","compute-07")).exchange().expectStatus().isForbidden();
        post("/incidents").bodyValue(Map.of("title","missing","severity","INFO","deviceId","other-org-device")).exchange().expectStatus().isNotFound();
        get("/incidents?limit=1000").exchange().expectStatus().isBadRequest();
        post("/investigations").bodyValue(Map.of("ip","example.com","port",443,"protocol","TCP","at",Instant.now().toString(),"direction","PUBLIC_TO_PRIVATE")).exchange().expectStatus().isBadRequest();
    }
    @Test void changedCheckDefinitionCannotDisplayOrAcceptAnOldTargetsPass(){
        var check=created("/checks",Map.of("deviceId","lab-sw-01","name","Revision fixture","type","TCP","target","192.0.2.2:22","intervalSeconds",60,"enabled",true,"provenance","SYNTHETIC"));
        var result=new HashMap<String,Object>(Map.of("id","old-"+UUID.randomUUID(),"checkId",check.get("id"),"observedAt",Instant.now().toString(),"status","PASS","latencyMs",10,"source","fixture","provenance","SYNTHETIC","definitionRevision",1));
        created("/check-results",result);
        post("/checks/"+check.get("id")+"/updates").bodyValue(Map.of("revision",1,"name","Changed target","target","192.0.2.99:22","intervalSeconds",60,"enabled",true))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.lastResult").isEmpty();
        result.put("id","late-"+UUID.randomUUID());post("/check-results").bodyValue(result).exchange().expectStatus().isEqualTo(409);
        result.put("definitionRevision",2);created("/check-results",result);
        get("/checks/"+check.get("id")).exchange().expectStatus().isOk().expectBody().jsonPath("$.lastResult.definitionRevision").isEqualTo(2);
    }
}
