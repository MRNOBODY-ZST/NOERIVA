package io.noeriva.control;

import com.sun.net.httpserver.*;
import io.noeriva.control.devices.TargetPolicy;
import java.net.*;
import java.security.KeyStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
import static io.noeriva.control.WorkbenchModels.*;
import static io.noeriva.control.SecurityConfiguration.Operator;

class CheckExecutionTest {
    final TargetPolicy policy=new TargetPolicy("127.0.0.1/32,::1/128");
    final TestWorkbench service=new TestWorkbench();
    final CheckExecution execution=new CheckExecution(service,policy,new DefaultListableBeanFactory().getBeanProvider(DatabaseClient.class),new MockEnvironment());
    final List<AutoCloseable> close=new ArrayList<>();
    final Operator operator=new Operator("op","","org",List.of("OPERATOR"));
    static Check check(String type,String target){return new Check("check","device","Target",type,target,60,true,false,"MANUAL","NATIVE_WORKER",2,"op",Instant.now(),Instant.now(),null);}
    @AfterEach void shutdown()throws Exception{execution.close();policy.close();service.close();for(var item:close)item.close();}
    HttpServer http(int code,AtomicInteger requests)throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
        server.createContext("/",exchange->{requests.incrementAndGet();assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();if(code==302)exchange.getResponseHeaders().set("Location","http://169.254.169.254/latest/meta-data");exchange.sendResponseHeaders(code,-1);exchange.close();});
        server.start();close.add(()->server.stop(0));return server;
    }
    @Test void tcpConnectUsesActualLocalListenerAndPersistsObservedRevision()throws Exception {
        try(var server=new java.net.ServerSocket(0,4,InetAddress.ofLiteral("127.0.0.1"))) {
            service.value=check("TCP","127.0.0.1:"+server.getLocalPort());
            CheckResult result=execution.run(operator,"check",2).block(Duration.ofSeconds(5));
            assertThat(result.status()).isEqualTo("PASS");assertThat(result.definitionRevision()).isEqualTo(2);assertThat(result.source()).isEqualTo("noeriva-native-tcp");assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
            assertThat(service.actor.organizationId()).isEqualTo("org");assertThat(service.results).hasSize(1);
        }
    }
    @Test void refusedConnectionIsARealFailureNotZeroLatencyHealthy()throws Exception {
        int port;try(var server=new java.net.ServerSocket(0)){port=server.getLocalPort();}
        var result=execution.observe(check("TCP","127.0.0.1:"+port)).block(Duration.ofSeconds(5));
        assertThat(result.status()).isEqualTo("FAIL");assertThat(result.message()).contains("CONNECTION_REFUSED");
    }
    @Test void httpDistinguishesStatusFailureAndDoesNotFollowMetadataRedirect()throws Exception {
        var requests=new AtomicInteger();var missing=http(503,requests);
        assertThat(execution.observe(check("HTTP","http://127.0.0.1:"+missing.getAddress().getPort()+"/health")).block().status()).isEqualTo("FAIL");
        var redirect=http(302,requests);
        assertThat(execution.observe(check("HTTP","http://127.0.0.1:"+redirect.getAddress().getPort()+"/")).block().message()).contains("重定向未跟随");
        assertThat(requests).hasValue(2);
    }
    @Test void policyDenialIsUnknownAndNeverConnectsToReservedEndpoints() {
        var result=execution.observe(check("HTTP","http://169.254.169.254/latest/meta-data")).block();
        assertThat(result.status()).isEqualTo("UNKNOWN");assertThat(result.message()).isEqualTo("TARGET_NOT_ALLOWED");
        assertThat(execution.observe(check("TCP","192.0.2.1:22")).block().message()).isEqualTo("TARGET_NOT_ALLOWED");
    }
    @Test void normalTlsTrustValidationRejectsAnUntrustedServer()throws Exception {
        KeyStore store=KeyStore.getInstance("PKCS12");try(var in=getClass().getResourceAsStream("/redfish/synthetic-tls.p12")){store.load(in,"synthetic-test-only".toCharArray());}
        KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,"synthetic-test-only".toCharArray());
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(keys.getKeyManagers(),null,null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),4);server.setHttpsConfigurator(new HttpsConfigurator(tls));server.createContext("/",exchange->{exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();close.add(()->server.stop(0));
        var result=execution.observe(check("HTTPS","https://127.0.0.1:"+server.getAddress().getPort())).block(Duration.ofSeconds(5));
        assertThat(result.status()).isEqualTo("FAIL");assertThat(result.message()).contains("TLS_VERIFICATION_OR_HANDSHAKE_FAILED");
    }
    @Test void endpointParsingRejectsCredentialsSchemeConfusionAndDnsLiterals() {
        assertThatThrownBy(()->CheckExecution.endpoint("HTTPS","http://127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->CheckExecution.endpoint("HTTP","http://user:secret@127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->CheckExecution.endpoint("TCP","127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->CheckExecution.endpoint("DNS","127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CheckExecution.endpoint("TLS","[::1]:443").host()).isEqualTo("::1");
    }
    @Test void dnsResolvesARealHostnameThroughTheAllowedAddressPolicy() {
        var result=execution.observe(check("DNS","localhost")).block(Duration.ofSeconds(5));
        assertThat(result.status()).isEqualTo("PASS");assertThat(result.message()).contains("DNS 解析成功");
    }
    @Test void readsAreScopedAndViewerStaleAndSyntheticRunsAreRejectedBeforeExecution() {
        service.value=check("TCP","127.0.0.1:9");
        assertThatThrownBy(()->execution.run(new Operator("reader","","org",List.of("VIEWER")),"check",2).block()).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->execution.run(new Operator("op","","other",List.of("OPERATOR")),"check",2).block()).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->execution.run(operator,"check",1).block()).isInstanceOf(ApiException.class);
        var c=service.value;service.value=new Check(c.id(),c.deviceId(),c.name(),c.type(),c.target(),60,true,false,"SYNTHETIC","COLLECTOR_REPORTED",2,"op",c.createdAt(),c.updatedAt(),null);
        assertThatThrownBy(()->execution.run(operator,"check",2).block()).isInstanceOf(ApiException.class);assertThat(service.results).isEmpty();
    }
    @Test void overlappingRunsAreRejectedAndCancellationReleasesTheLeaseAndSocket()throws Exception {
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
        var threads=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(threads);
        server.createContext("/",exchange->{entered.countDown();try{finish.await(5,TimeUnit.SECONDS);exchange.sendResponseHeaders(200,-1);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});
        server.start();close.add(()->{finish.countDown();server.stop(0);threads.shutdownNow();});
        service.value=check("HTTP","http://127.0.0.1:"+server.getAddress().getPort());
        var first=execution.run(operator,"check",2).toFuture();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(()->execution.run(operator,"check",2).block()).isInstanceOf(ApiException.class);
        first.cancel(true);finish.countDown();
        assertThat(execution.run(operator,"check",2).block(Duration.ofSeconds(5)).status()).isEqualTo("PASS");
        assertThat(service.results).hasSize(1);
    }
    static class TestWorkbench extends WorkbenchService {
        Check value;Operator actor;final List<CheckResultInput> results=new CopyOnWriteArrayList<>();
        TestWorkbench(){super(null,null);}
        @Override public Mono<Check> check(Operator actor,String id){return "org".equals(actor.organizationId())?Mono.just(value):Mono.error(ApiException.missing());}
        @Override public Mono<CheckResult> reportResult(Operator actor,CheckResultInput input){this.actor=actor;results.add(input);return Mono.just(new CheckResult(input.id(),input.checkId(),input.observedAt(),input.status(),input.latencyMs(),input.message(),input.source(),input.provenance(),Instant.now(),input.definitionRevision()));}
    }
}
