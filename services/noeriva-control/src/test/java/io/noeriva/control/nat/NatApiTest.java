package io.noeriva.control.nat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.util.Map;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"NOERIVA_BOOTSTRAP_PASSWORD=noeriva-local-demo","NOERIVA_COLLECTOR_PASSWORD=noeriva-local-demo"})
@AutoConfigureWebTestClient @ActiveProfiles("demo")
@org.springframework.context.annotation.Import(NatApiTest.Users.class)
class NatApiTest {
 @org.springframework.boot.test.context.TestConfiguration static class Users {
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
  org.springframework.security.core.userdetails.ReactiveUserDetailsService natUsers(org.springframework.security.crypto.password.PasswordEncoder encoder){String hash=encoder.encode("noeriva-local-demo");return name->java.util.Set.of("admin","viewer","operator").contains(name)?reactor.core.publisher.Mono.just(new io.noeriva.control.SecurityConfiguration.Operator(name,hash,"demo",java.util.List.of(name.toUpperCase()))):reactor.core.publisher.Mono.empty();}
 }
 @Autowired WebTestClient client;
 @Test void authenticatedViewerCanReadSourceStateWithoutInventedFixture(){client.get().uri("/api/v1/nat-audit/sources").headers(h->h.setBasicAuth("viewer","noeriva-local-demo")).exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(0);}
 @Test void viewerAndOperatorCannotConfigureEvenWithRequestHeader(){for(String role:new String[]{"viewer","operator"})for(String action:new String[]{"settings","state"})client.post().uri("/api/v1/nat-audit/devices/gateway/"+action).headers(h->h.setBasicAuth(role,"noeriva-local-demo")).header("X-Noeriva-Request","1").bodyValue(Map.of("revision",1,"enabled",true)).exchange().expectStatus().isForbidden();}
 @Test void administratorStillRequiresExplicitMutationHeader(){client.post().uri("/api/v1/nat-audit/devices/gateway/settings").headers(h->h.setBasicAuth("admin","noeriva-local-demo")).bodyValue(Map.of("revision",0,"enabled",true)).exchange().expectStatus().isForbidden();}
 @Test void queryBoundsAreCheckedBeforeUnavailableStorage(){client.get().uri("/api/v1/nat-audit/events?deviceId=gateway&from=2026-09-07T00:00:00Z&to=2026-09-01T00:00:00Z").headers(h->h.setBasicAuth("viewer","noeriva-local-demo")).exchange().expectStatus().isBadRequest();client.get().uri("/api/v1/nat-audit/sources").exchange().expectStatus().isUnauthorized();}
}
