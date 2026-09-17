package io.noeriva.control.devices;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static io.noeriva.control.devices.DeviceProtocol.*;

/** Official Redfish resource shapes; every response and certificate is synthetic. No hardware claim. */
class RedfishDriverTest {
    final Map<String,Reply> replies = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger rejectedAuth = new AtomicInteger();
    HttpsServer server; ExecutorService executor; String pin; SSLContext trusted;
    static final Secrets SECRET = new Secrets(null,null,null,"synthetic-password");
    record Reply(int status, String body, boolean chunked, int delayMillis) {
        Reply(int status,String body) { this(status,body,false,0); }
    }
    String password="synthetic-password";
    String bindAddress="127.0.0.1";
    @BeforeEach void start() throws Exception { startServer("synthetic-tls.p12"); baseline("Dell Inc.","iDRAC 9"); }
    void startServer(String file) throws Exception {
        KeyStore ks=KeyStore.getInstance("PKCS12");
        try(var in=getClass().getResourceAsStream("/redfish/"+file)) { ks.load(in,"synthetic-test-only".toCharArray()); }
        X509Certificate cert=(X509Certificate)ks.getCertificate("synthetic");
        pin=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(ks,"synthetic-test-only".toCharArray());
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(ks);
        SSLContext context=SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        trusted=SSLContext.getInstance("TLS"); trusted.init(null,tm.getTrustManagers(),null);
        server=HttpsServer.create(new InetSocketAddress(bindAddress,0),16);
        server.setHttpsConfigurator(new HttpsConfigurator(context)); executor=Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String path=exchange.getRequestURI().toString(); requests.add(path);
            String expected="Basic "+Base64.getEncoder().encodeToString(("monitor:"+password).getBytes(StandardCharsets.UTF_8));
            Reply reply;
            if(!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) { rejectedAuth.incrementAndGet(); reply=new Reply(401,"{}"); }
            else reply=replies.getOrDefault(path,new Reply(404,"{}"));
            byte[] body=reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if(reply.status()==302) exchange.getResponseHeaders().set("Location","https://example.invalid/secret");
            if(reply.delayMillis()>0) try { Thread.sleep(reply.delayMillis()); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(reply.status(),reply.chunked()?0:body.length);
            try(var out=exchange.getResponseBody()) { out.write(body); } catch(java.io.IOException ignored) {} exchange.close();
        }); server.start();
    }
    @AfterEach void stop() { if(server!=null) server.stop(0); if(executor!=null) executor.shutdownNow(); }
    void json(String path,String body) { replies.put(path,new Reply(200,body)); }
    void baseline(String vendor,String manager) {
        json("/redfish/v1","""
            {"@odata.type":"#ServiceRoot.v1_5_0.ServiceRoot","RedfishVersion":"1.5.0","Systems":{"@odata.id":"/redfish/v1/Systems"},"Managers":{"@odata.id":"/redfish/v1/Managers"},"Chassis":{"@odata.id":"/redfish/v1/Chassis"}}
            """);
        json("/redfish/v1/Systems","{\"Members\":[{\"@odata.id\":\"/redfish/v1/Systems/arbitrary-system\"}]}");
        json("/redfish/v1/Systems/arbitrary-system","""
            {"@odata.type":"#ComputerSystem.v1_6_0.ComputerSystem","Id":"arbitrary-system","Manufacturer":"%s","Model":"SYNTHETIC-SERVER","SerialNumber":"SYNTHETIC-001","HostName":"synthetic-host","BIOSVersion":"1.0-test","Status":{"HealthRollup":"Warning"},"PowerState":"On","ProcessorSummary":{"Count":2},"MemorySummary":{"TotalSystemMemoryGiB":128}}
            """.formatted(vendor));
        json("/redfish/v1/Managers","{\"Members\":[{\"@odata.id\":\"/redfish/v1/Managers/BMC\"}]}");
        json("/redfish/v1/Managers/BMC","{\"Id\":\"BMC\",\"Manufacturer\":\"%s\",\"Model\":\"%s\",\"FirmwareVersion\":\"synthetic-3.0\"}".formatted(vendor,manager));
        json("/redfish/v1/Chassis","{\"Members\":[{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x\"}]}");
        json("/redfish/v1/Chassis/enclosure-x","{\"Id\":\"enclosure-x\",\"Thermal\":{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/Thermal\"},\"Power\":{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/Power\"}}");
        json("/redfish/v1/Chassis/enclosure-x/Thermal","""
            {"Temperatures":[{"MemberId":"inlet","Name":"Inlet","ReadingCelsius":23.5,"Status":{"Health":"OK"}},{"MemberId":"absent","Name":"Missing","ReadingCelsius":null}],"Fans":[{"MemberId":"fan1","Name":"Fan 1","Reading":7200,"ReadingUnits":"RPM","Status":{"Health":"OK"}},{"MemberId":"fan2","Name":"Fan 2","Reading":38,"ReadingUnits":"Percent"}]}
            """);
        json("/redfish/v1/Chassis/enclosure-x/Power","""
            {"PowerControl":[{"MemberId":"total","Name":"System Power","PowerConsumedWatts":180}],"Voltages":[{"MemberId":"v1","Name":"12V","ReadingVolts":12.1}],"PowerSupplies":[{"MemberId":"psu1","Name":"PSU 1","PowerInputWatts":190,"PowerOutputWatts":180,"Status":{"Health":"OK"}}]}
            """);
    }
    Target target(String host,String mode,String fingerprint) { return new Target(host,"127.0.0.1",server.getAddress().getPort(),"REDFISH","monitor",null,null,null,null,null,mode,fingerprint,1500,128); }
    Reading read() { return new RedfishDriver().read(target("synthetic-bmc.invalid","PINNED",pin),SECRET).block(Duration.ofSeconds(10)); }
    void failure(Runnable action,String code) { assertThatThrownBy(action::run).isInstanceOfSatisfying(Failure.class, f -> { assertThat(f.code()).isEqualTo(code); assertThat(f.getMessage()).doesNotContain("synthetic-password","Authorization"); }); }

    @Test void inspurFirmwareNumericStringsAndDisableStatePreserveUnitsAndMissingValues() {
        baseline("Inspur","BMC");
        json("/redfish/v1/Chassis/enclosure-x/Thermal","""
            {"Temperatures":[{"Name":"Inlet","ReadingCelsius":22,"Status":{"State":"Enabled","Health":"OK"}},{"Name":"Uninstalled GPU","ReadingCelsius":0,"Status":{"State":"DISABLE","Health":"NA"}}],
             "Fans":[{"Name":"Front Fan","Reading":"4224","ReadingUnits":"RPM","Status":{"State":"Enabled","Health":"OK"}},{"Name":"Invalid Fan","Reading":"NaN","ReadingUnits":"RPM"}]}
            """);
        Reading r=read();
        assertThat(r.sensors()).anySatisfy(s->{assertThat(s.label()).isEqualTo("Front Fan");assertThat(s.value()).isEqualTo(4224);assertThat(s.unit()).isEqualTo("RPM");});
        assertThat(r.sensors()).noneSatisfy(s->assertThat(s.label()).isIn("Uninstalled GPU","Invalid Fan"));
        assertThat(r.metrics()).containsEntry("temperature_celsius",22.0);
        assertThat(r.qualityFlags()).contains("INSPUR_NUMERIC_STRING","READING_UNAVAILABLE","INVALID_READING");
    }
    @Test void genericNumericStringsAreFlaggedInsteadOfSilentlyCoerced() {
        baseline("Other Vendor","BMC");
        json("/redfish/v1/Chassis/enclosure-x/Thermal","{\"Fans\":[{\"Reading\":\"4224\",\"ReadingUnits\":\"RPM\"}]}");
        Reading r=read();
        assertThat(r.sensors()).noneSatisfy(s->assertThat(s.metric()).isEqualTo("fanSpeed"));
        assertThat(r.qualityFlags()).contains("INVALID_READING");
    }
    @ParameterizedTest @CsvSource({"Dell Inc.,iDRAC 9,DELL,IDRAC", "Huawei,iBMC,HUAWEI,IBMC", "Inspur,BMC,INSPUR,BMC", "H3C,HDM,H3C,HDM", "Other Vendor,BMC,GENERIC,REDFISH"})
    void discoversVendorAndLegacySensorsUsingRealTls(String vendor,String model,String expectedVendor,String family) {
        baseline(vendor,model); Reading r=read();
        assertThat(r.identity().vendor()).isEqualTo(expectedVendor); assertThat(r.identity().family()).isEqualTo(family);
        assertThat(r.identity().model()).isEqualTo("SYNTHETIC-SERVER"); assertThat(r.identity().firmware()).isEqualTo("synthetic-3.0");
        assertThat(r.health()).isEqualTo("WARNING"); assertThat(r.metrics()).doesNotContainKeys("cpu_percent","memory_percent");
        assertThat(r.metrics()).containsEntry("temperature_celsius",23.5).containsEntry("power_watts",180.0);
        assertThat(r.facts()).containsEntry("temperatureSummary","maximum_of_collected_temperature_sensors");
        assertThat(r.facts().get("powerSource")).contains("/Power#/PowerControl/0/PowerConsumedWatts");
        assertThat(r.sensors()).anySatisfy(s -> { assertThat(s.metric()).isEqualTo("temperature"); assertThat(s.value()).isEqualTo(23.5); assertThat(s.unit()).isEqualTo("Cel"); assertThat(s.sourceRef()).contains("Thermal#/Temperatures/0"); });
        assertThat(r.sensors()).anySatisfy(s -> { assertThat(s.metric()).isEqualTo("fanSpeed"); assertThat(s.unit()).isEqualTo("%"); assertThat(s.value()).isEqualTo(38.0); });
        assertThat(r.sensors()).noneMatch(s -> "Missing".equals(s.label()));
        assertThat(r.capabilities()).contains("inventory","temperature","fanSpeed","power","voltage");
        assertThat(r.facts()).containsEntry("authentication","HTTPS_BASIC");
        assertThat(rejectedAuth).hasValue(0);
    }
    @Test void systemTrustUsesOriginalHostnameAndFixedAddress() {
        Reading r=new RedfishDriver(trusted).read(target("localhost","SYSTEM",null),SECRET).block(); assertThat(r.identity().vendor()).isEqualTo("DELL");
        failure(() -> new RedfishDriver(trusted).read(target("wrong-name.invalid","SYSTEM",null),SECRET).block(),"TLS_VALIDATION_FAILED");
    }
    @Test void rejectsWrongPinAndUntrustedSystemCertificate() {
        failure(() -> new RedfishDriver().read(target("localhost","PINNED","0".repeat(64)),SECRET).block(),"TLS_VALIDATION_FAILED");
        failure(() -> new RedfishDriver().read(target("localhost","SYSTEM",null),SECRET).block(),"TLS_VALIDATION_FAILED");
        assertThat(requests).isEmpty();
    }
    @Test void matchingPinDoesNotOverrideExpiredCertificate() throws Exception {
        stop(); startServer("synthetic-expired-tls.p12");
        failure(this::read,"TLS_VALIDATION_FAILED"); assertThat(requests).isEmpty();
    }
    @Test void wrongPasswordFailsOnceWithoutFallbackOrCredentialLeak() {
        failure(() -> new RedfishDriver().read(target("localhost","PINNED",pin),new Secrets(null,null,null,"invalid-synthetic")).block(),"AUTHENTICATION_FAILED");
        assertThat(requests).hasSize(1); assertThat(rejectedAuth).hasValue(1);
    }
    @Test void blocksCrossOriginAndEncodedTraversalWithoutFollowingLinks() {
        for(String link: List.of("https://other.invalid/redfish/v1/Chassis","/redfish/v1/%2e%2e/private","/redfish/v1/../private","//other.invalid/redfish/v1/Chassis")) {
            requests.clear(); json("/redfish/v1/Chassis","{\"Members\":[{\"@odata.id\":\""+link+"\"}]}");
            failure(this::read,"UNSAFE_RESOURCE_LINK"); assertThat(requests).doesNotContain(link);
        }
    }
    @Test void doesNotFollowRedirect() { replies.put("/redfish/v1",new Reply(302,"{}")); failure(this::read,"REDIRECT_REJECTED"); assertThat(requests).containsExactly("/redfish/v1"); }
    @Test void permissionOrMissingAdvertisedResourceIsPartial() {
        replies.put("/redfish/v1/Chassis/enclosure-x/Thermal",new Reply(403,"{}"));
        replies.put("/redfish/v1/Chassis/enclosure-x/Power",new Reply(404,"{}"));
        Reading r=read(); assertThat(r.qualityFlags()).contains("RESOURCE_FORBIDDEN","RESOURCE_NOT_FOUND","PARTIAL");
        assertThat(r.capabilities()).doesNotContain("temperature","power"); assertThat(r.sensors()).isEmpty();
    }
    @Test void readsModernSensorsAndSubsystemExcerptUnits() {
        json("/redfish/v1/Chassis/enclosure-x","""
            {"Id":"enclosure-x","Sensors":{"@odata.id":"/redfish/v1/Chassis/enclosure-x/Sensors"},"ThermalSubsystem":{"@odata.id":"/redfish/v1/Chassis/enclosure-x/ThermalSubsystem"},"PowerSubsystem":{"@odata.id":"/redfish/v1/Chassis/enclosure-x/PowerSubsystem"}}
            """);
        json("/redfish/v1/Chassis/enclosure-x/Sensors","{\"Members\":[{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/Sensors/temp1\"}],\"Members@odata.nextLink\":\"/redfish/v1/Chassis/enclosure-x/Sensors?page=2\"}");
        json("/redfish/v1/Chassis/enclosure-x/Sensors?page=2","{\"Members\":[{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/Sensors/current\"}]}");
        json("/redfish/v1/Chassis/enclosure-x/Sensors/temp1","{\"Id\":\"temp1\",\"Name\":\"CPU Temp\",\"Reading\":52.0,\"ReadingUnits\":\"Cel\",\"ReadingType\":\"Temperature\",\"Status\":{\"Health\":\"Warning\"}}");
        json("/redfish/v1/Chassis/enclosure-x/Sensors/current","{\"Id\":\"current\",\"Name\":\"Input current\",\"Reading\":1.2,\"ReadingUnits\":\"A\",\"ReadingType\":\"Current\"}");
        json("/redfish/v1/Chassis/enclosure-x/ThermalSubsystem","{\"Fans\":{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/ThermalSubsystem/Fans\"}}");
        json("/redfish/v1/Chassis/enclosure-x/ThermalSubsystem/Fans","{\"Members\":[{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/ThermalSubsystem/Fans/1\"}]}");
        json("/redfish/v1/Chassis/enclosure-x/ThermalSubsystem/Fans/1","{\"Id\":\"1\",\"Name\":\"Fan X\",\"SpeedPercent\":{\"Reading\":80}}");
        json("/redfish/v1/Chassis/enclosure-x/PowerSubsystem","{\"PowerSupplies\":{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/PowerSubsystem/PowerSupplies\"}}");
        json("/redfish/v1/Chassis/enclosure-x/PowerSubsystem/PowerSupplies","{\"Members\":[{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/PowerSubsystem/PowerSupplies/1\"}]}");
        json("/redfish/v1/Chassis/enclosure-x/PowerSubsystem/PowerSupplies/1","{\"Id\":\"1\",\"Name\":\"PSU X\",\"Metrics\":{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/PowerSubsystem/PowerSupplies/1/Metrics\"}}");
        json("/redfish/v1/Chassis/enclosure-x/PowerSubsystem/PowerSupplies/1/Metrics","{\"InputPowerWatts\":{\"Reading\":221},\"OutputPowerWatts\":{\"Reading\":200}}");
        Reading r=read(); assertThat(r.capabilities()).contains("temperature","current","fanSpeed","power");
        assertThat(r.sensors()).anyMatch(s -> "fanSpeed".equals(s.metric()) && s.value()==80.0 && "%".equals(s.unit()));
        assertThat(r.sensors()).anyMatch(s -> "power".equals(s.metric()) && s.value()==221.0);
        assertThat(requests).contains("/redfish/v1/Chassis/enclosure-x/Sensors?page=2");
    }
    @Test void boundsSensorCountAndRejectsOversizedResponse() {
        String sensors=java.util.stream.IntStream.range(0,200).mapToObj(i -> "{\"MemberId\":\""+i+"\",\"ReadingCelsius\":20}").collect(java.util.stream.Collectors.joining(","));
        json("/redfish/v1/Chassis/enclosure-x/Thermal","{\"Temperatures\":["+sensors+"]}");
        Reading r=read(); assertThat(r.sensors()).hasSize(128); assertThat(r.qualityFlags()).contains("SENSOR_LIMIT","PARTIAL");
        json("/redfish/v1","{\"huge\":\""+"x".repeat(2*1024*1024)+"\"}"); failure(this::read,"RESPONSE_TOO_LARGE");
    }
    @Test void boundsGetRequestsAndCollectionLoops() {
        String members=java.util.stream.IntStream.range(0,100).mapToObj(i -> {
            json("/redfish/v1/Systems/"+i,"{\"Id\":\""+i+"\",\"Manufacturer\":\"Dell\"}");
            return "{\"@odata.id\":\"/redfish/v1/Systems/"+i+"\"}";
        }).collect(java.util.stream.Collectors.joining(","));
        json("/redfish/v1/Systems","{\"Members\":["+members+"]}");
        Reading r=read(); assertThat(requests.size()).isLessThanOrEqualTo(80); assertThat(r.qualityFlags()).contains("REQUEST_LIMIT","PARTIAL");
    }
    @Test void missingProtocolRootDoesNotInventSupport() { replies.put("/redfish/v1",new Reply(404,"{}")); failure(this::read,"PROTOCOL_UNSUPPORTED"); }
    @Test void ignoresUnknownOemNumbersAndDoesNotInventCpuUtilization() {
        json("/redfish/v1/Chassis/enclosure-x/Thermal","{\"Oem\":{\"Vendor\":{\"cpuUsage\":99}},\"Fans\":[{\"Reading\":42,\"ReadingUnits\":\"unknown\"}]}");
        Reading r=read(); assertThat(r.metrics()).doesNotContainKeys("cpuPercent","memoryPercent"); assertThat(r.sensors()).noneMatch(s -> "fanSpeed".equals(s.metric()));
        assertThat(r.qualityFlags()).contains("UNSUPPORTED_UNIT");
    }
    @Test void mapsStandardOkToPlatformHealthyWithoutDeclaringMissingHealthGood() {
        json("/redfish/v1/Systems/arbitrary-system","{\"Manufacturer\":\"Dell\",\"Status\":{\"Health\":\"OK\"}}");
        Reading r=read(); assertThat(r.health()).isEqualTo("HEALTHY");
        assertThat(r.sensors()).anyMatch(x -> "HEALTHY".equals(x.health()));
        json("/redfish/v1/Systems/arbitrary-system","{\"Manufacturer\":\"Dell\"}");
        assertThat(read().health()).isEqualTo("UNKNOWN");
    }
    @Test void unavailableLegacyPowerControlCannotBecomeWholeDeviceSummary() {
        json("/redfish/v1/Chassis/enclosure-x/Power","{\"PowerControl\":[{\"PowerConsumedWatts\":500,\"Status\":{\"State\":\"Disabled\"}}]}");
        Reading r=read(); assertThat(r.metrics()).doesNotContainKey("power_watts");
        assertThat(r.qualityFlags()).contains("READING_UNAVAILABLE");
    }
    @Test void unavailableEnvironmentPowerCannotBecomeWholeDeviceSummary() {
        json("/redfish/v1/Chassis/enclosure-x","{\"EnvironmentMetrics\":{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/EnvironmentMetrics\"}}");
        json("/redfish/v1/Chassis/enclosure-x/EnvironmentMetrics","{\"PowerWatts\":{\"Reading\":500,\"Status\":{\"State\":\"Absent\"}}}");
        Reading r=read(); assertThat(r.metrics()).doesNotContainKey("power_watts");
        assertThat(r.qualityFlags()).contains("READING_UNAVAILABLE");
    }
    @Test void summaryUsesHighestCollectedTemperatureButNeverAddsPsuOrFanPower() {
        json("/redfish/v1/Chassis/enclosure-x/Thermal","{\"Temperatures\":[{\"Name\":\"Inlet\",\"ReadingCelsius\":24},{\"Name\":\"CPU\",\"ReadingCelsius\":62}]}");
        Reading r=read(); assertThat(r.metrics()).containsEntry("temperature_celsius",62.0).containsEntry("power_watts",180.0);
        assertThat(r.facts().get("temperatureSource")).endsWith("#/Temperatures/1");
        json("/redfish/v1/Chassis/enclosure-x/Power","{\"PowerSupplies\":[{\"PowerInputWatts\":200,\"PowerOutputWatts\":180}]}");
        assertThat(read().metrics()).doesNotContainKey("power_watts");
    }
    @Test void refusesWholeDevicePowerWhenMultipleChassisOrPowerDomainsExist() {
        json("/redfish/v1/Chassis","{\"Members\":[{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x\"},{\"@odata.id\":\"/redfish/v1/Chassis/other\"}]}");
        json("/redfish/v1/Chassis/other","{\"Id\":\"other\"}");
        assertThat(read().metrics()).doesNotContainKey("power_watts");
        baseline("Dell","iDRAC 9");
        json("/redfish/v1/Chassis/enclosure-x/Power","{\"PowerControl\":[{\"PowerConsumedWatts\":180},{\"PowerConsumedWatts\":200}]}");
        Reading r=read(); assertThat(r.metrics()).doesNotContainKey("power_watts"); assertThat(r.facts()).containsEntry("powerSummary","ambiguous_or_unavailable");
    }
    @Test void uniqueChassisEnvironmentMetricsProvidesPowerButConflictingDuplicateDoesNot() {
        json("/redfish/v1/Chassis/enclosure-x","{\"Id\":\"enclosure-x\",\"EnvironmentMetrics\":{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/EnvironmentMetrics\"}}");
        json("/redfish/v1/Chassis/enclosure-x/EnvironmentMetrics","{\"PowerWatts\":{\"Reading\":221},\"TemperatureCelsius\":{\"Reading\":26}}");
        assertThat(read().metrics()).containsEntry("power_watts",221.0).containsEntry("temperature_celsius",26.0);
        json("/redfish/v1/Chassis/enclosure-x","{\"Power\":{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/Power\"},\"EnvironmentMetrics\":{\"@odata.id\":\"/redfish/v1/Chassis/enclosure-x/EnvironmentMetrics\"}}");
        assertThat(read().metrics()).doesNotContainKey("power_watts");
    }
    @Test void readsChunkedBodyAndRejectsOversizedChunkStream() {
        Reply normal=replies.get("/redfish/v1"); replies.put("/redfish/v1",new Reply(200,normal.body(),true,0));
        assertThat(read().identity().vendor()).isEqualTo("DELL");
        replies.put("/redfish/v1",new Reply(200,"{\"large\":\""+"x".repeat(2*1024*1024)+"\"}",true,0));
        failure(this::read,"RESPONSE_TOO_LARGE");
    }
    @Test void slowResponseHonorsSocketTimeout() {
        replies.put("/redfish/v1",new Reply(200,replies.get("/redfish/v1").body(),false,2200));
        long started=System.nanoTime(); failure(this::read,"TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(3));
    }
    @Test void h3cLegacyStringMembersAndPageCycleRemainBounded() {
        baseline("H3C","HDM");
        json("/redfish/v1/Systems","{\"Members\":[\"/redfish/v1/Systems/arbitrary-system\"],\"Members@odata.nextLink\":\"/redfish/v1/Systems\"}");
        Reading r=read(); assertThat(r.identity().vendor()).isEqualTo("H3C");
        assertThat(r.qualityFlags()).contains("LEGACY_STRING_MEMBER_LINK","COLLECTION_CYCLE","PARTIAL");
        assertThat(requests.stream().filter("/redfish/v1/Systems"::equals)).hasSize(1);
    }
    @Test void refusesAddressThatCouldTriggerASecondDnsResolution() {
        Target t=target("localhost","PINNED",pin);
        Target invalid=new Target(t.host(),"deadbeef",t.port(),t.protocol(),t.username(),null,null,null,null,null,t.tlsMode(),t.certificateSha256(),t.timeoutMillis(),128);
        failure(() -> new RedfishDriver().read(invalid,SECRET).block(),"INVALID_TARGET");
        assertThat(requests).isEmpty();
    }
    @Test void emptyIdentityResourceIsPartialAndDoesNotClaimInventoryCapability() {
        json("/redfish/v1/Systems/arbitrary-system","{}");
        Reading r=read(); assertThat(r.capabilities()).doesNotContain("inventory");
        assertThat(r.qualityFlags()).contains("INVALID_RESOURCE","PARTIAL");
    }
    @Test void malformedJsonNeverBecomesSuccessfulEmptyReading() {
        json("/redfish/v1","{invalid json"); failure(this::read,"INVALID_JSON_RESPONSE");
    }
    @Test void cancellationStopsTheReadBeforeAdditionalResources() throws Exception {
        replies.put("/redfish/v1",new Reply(200,replies.get("/redfish/v1").body(),false,1200));
        RedfishDriver driver=new RedfishDriver();
        var dropped=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        reactor.core.publisher.Hooks.onErrorDropped(dropped::set);
        try {
            var subscription=driver.read(target("localhost","PINNED",pin),SECRET).subscribe();
            long until=System.nanoTime()+Duration.ofSeconds(2).toNanos();
            while(requests.isEmpty() && System.nanoTime()<until) Thread.sleep(10);
            assertThat(requests).hasSize(1); subscription.dispose(); Thread.sleep(150);
            assertThat(requests).hasSize(1); assertThat(dropped.get()).isNull();
        } finally { driver.close(); reactor.core.publisher.Hooks.resetOnErrorDropped(); }
    }
    /** Manual CUA fixture. Bind intentionally reachable by local Docker; never use on production networks. */
    public static void main(String[] args) throws Exception {
        String password=System.getenv("NOERIVA_MOCK_REDFISH_PASSWORD");
        if(password==null || password.isBlank()) throw new IllegalArgumentException("NOERIVA_MOCK_REDFISH_PASSWORD is required");
        RedfishDriverTest fixture=new RedfishDriverTest(); fixture.password=password; fixture.bindAddress="0.0.0.0";
        fixture.start();
        Runtime.getRuntime().addShutdownHook(new Thread(fixture::stop));
        System.out.println("SYNTHETIC_ONLY port="+fixture.server.getAddress().getPort()+" sha256="+fixture.pin+" username=monitor");
        new CountDownLatch(1).await();
    }

}
