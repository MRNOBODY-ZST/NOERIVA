package io.noeriva.control;

import io.noeriva.query.RollupKey;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import static io.noeriva.control.Models.*;
import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static org.assertj.core.api.Assertions.*;

/** Runs the production SQL and transaction boundaries against an isolated database. */
@Testcontainers
class MySqlRepositoryIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
        .withDatabaseName("noeriva_test")
        .withUsername("noeriva_test")
        .withPassword("isolated-container-test-only");

    private static DatabaseClient db;
    private static MySqlRepository repository;
    private static MySqlRollupStateStore rollups;
    private String organization;
    private String otherOrganization;

    @BeforeAll
    static void database() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("classpath:db/migration").load().migrate();
        var factory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
            .option(DRIVER, "mysql").option(HOST, MYSQL.getHost())
            .option(PORT, MYSQL.getMappedPort(3306)).option(DATABASE, MYSQL.getDatabaseName())
            .option(USER, MYSQL.getUsername()).option(PASSWORD, MYSQL.getPassword())
            .option(Option.valueOf("sslMode"), "DISABLED")
            .option(Option.valueOf("allowPublicKeyRetrieval"), true).build());
        db = DatabaseClient.create(factory);
        var transactions = TransactionalOperator.create(new R2dbcTransactionManager(factory));
        var json = JsonMapper.builder().build();
        // These tests exercise MySQL primitives only; no history request is made.
        var history = new HistoryStore(WebClient.builder(), new MockEnvironment()
            .withProperty("NOERIVA_CLICKHOUSE_URL", "http://127.0.0.1:9")
            .withProperty("NOERIVA_CLICKHOUSE_PASSWORD", "unused-test-dependency"), json);
        repository = new MySqlRepository(db, transactions, json, history);
        rollups = new MySqlRollupStateStore(db, transactions);
    }

    @BeforeEach
    void organizations() {
        organization = "org-" + UUID.randomUUID();
        otherOrganization = "org-" + UUID.randomUUID();
        db.sql("INSERT INTO organization(id,name) VALUES(:org,'Integration A'),(:other,'Integration B')")
            .bind("org", organization).bind("other", otherOrganization).fetch().rowsUpdated().block(TIMEOUT);
        db.sql("INSERT INTO site(organization_id,id,name,timezone) VALUES(:org,'site-a','Integration','UTC')")
            .bind("org", organization).fetch().rowsUpdated().block(TIMEOUT);
    }

    @Test
    void alertSummaryIsExactBeyondQueuePageAndAcknowledgementDoesNotMeanRecovery() {
        Device device=createDevice();
        Flux.range(0,104).concatMap(i->db.sql("INSERT INTO alert(organization_id,id,device_id,device_name,severity,state,title,opened_at,revision) VALUES(:org,:id,:device,'Fixture','WARNING',:state,'Synthetic summary fixture',UTC_TIMESTAMP(6),1)")
            .bind("org",organization).bind("id","summary-"+i).bind("device",device.id()).bind("state",i<101?"OPEN":i<103?"ACKNOWLEDGED":"RESOLVED").fetch().rowsUpdated()).then().block(TIMEOUT);
        db.sql("INSERT INTO alert(organization_id,id,device_id,device_name,severity,state,title,opened_at,revision) VALUES(:org,'other','other','Other','CRITICAL','OPEN','Other tenant',UTC_TIMESTAMP(6),1)")
            .bind("org",otherOrganization).fetch().rowsUpdated().block(TIMEOUT);
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("db",db);
        var controller=new AlertSummaryController(repository,beans.getBeanProvider(DatabaseClient.class));
        var user=new SecurityConfiguration.Operator("tester","unused",organization,java.util.List.of("ADMIN"));
        var before=controller.summary(user,"").block(TIMEOUT);
        assertThat(before.open()).isEqualTo(101);assertThat(before.acknowledged()).isEqualTo(2);assertThat(before.resolved()).isEqualTo(1);assertThat(before.active()).isEqualTo(103);
        repository.acknowledge(organization,"tester","summary-0",1).block(TIMEOUT);
        var after=controller.summary(user,device.id()).block(TIMEOUT);
        assertThat(after.open()).isEqualTo(100);assertThat(after.acknowledged()).isEqualTo(3);assertThat(after.active()).isEqualTo(before.active());assertThat(after.resolved()).isEqualTo(1);
        assertThatThrownBy(()->controller.summary(user,"other").block(TIMEOUT)).isInstanceOf(ApiException.class);
    }

    @Test
    void createCommitsDeviceOutboxAndAuditTogetherAndRollsAllBackOnAuditFailure() {
        Device created = createDevice();
        assertThat(created.health()).isEqualTo("UNKNOWN");
        assertThat(count("device", organization)).isEqualTo(1);
        assertThat(count("outbox", organization)).isEqualTo(1);
        assertThat(count("control_audit", organization)).isEqualTo(1);
        assertThat(db.sql("SELECT aggregate_id FROM outbox WHERE organization_id=:org AND kind='DeviceCreated'")
            .bind("org", organization).map((row, metadata) -> row.get("aggregate_id", String.class))
            .one().block(TIMEOUT)).isEqualTo(created.id());

        // The device and outbox insert run before this oversized audit actor is rejected by MySQL.
        assertThatThrownBy(() -> repository.create(organization, "x".repeat(121), deviceInput())
            .block(TIMEOUT)).isInstanceOf(RuntimeException.class);
        assertThat(count("device", organization)).isEqualTo(1);
        assertThat(count("outbox", organization)).isEqualTo(1);
        assertThat(count("control_audit", organization)).isEqualTo(1);
    }

    @Test
    void otherOrganizationCannotReadProjectOrCreateUsingAnotherOrganizationsSite() {
        Device created = createDevice();
        assertThat(repository.device(otherOrganization, created.id()).block(TIMEOUT)).isNull();
        assertThat(repository.deviceBatch(otherOrganization, java.util.List.of(created.id()))
            .collectList().block(TIMEOUT)).isEmpty();
        assertThatThrownBy(() -> repository.create(otherOrganization, "actor", deviceInput()).block(TIMEOUT))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> repository.project(otherOrganization,
            observation(created.id(), "host", "boot-a", 1, now(), "HEALTHY")).block(TIMEOUT))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(count("device", otherOrganization)).isZero();
        assertThat(count("source_current", otherOrganization)).isZero();
        assertThat(count("outbox", otherOrganization)).isZero();
        assertThat(count("control_audit", otherOrganization)).isZero();
        assertThat(repository.device(organization, created.id()).block(TIMEOUT).health()).isEqualTo("UNKNOWN");
    }

    @Test
    void replayAndOlderEpochNeverRefreshSourceTimeOrDeviceRevision() {
        Device created = createDevice();
        Instant old = now().minusSeconds(600);
        assertThat(project(observation(created.id(), "host", "boot-a", 100, old, "HEALTHY"))).isTrue();
        Device before = repository.device(organization, created.id()).block(TIMEOUT);
        assertThat(project(observation(created.id(), "host", "boot-a", 100, now(), "CRITICAL"))).isFalse();
        assertThat(project(observation(created.id(), "host", "boot-a", 99, now(), "CRITICAL"))).isFalse();
        assertThat(project(observation(created.id(), "host", "boot-b", 1, old.minusSeconds(1), "CRITICAL"))).isFalse();
        SourceState unchanged = source(created.id(), "host");
        assertThat(unchanged.observedAt()).isEqualTo(old);
        assertThat(unchanged.sequence()).isEqualTo(100);
        assertThat(unchanged.freshness()).isEqualTo("STALE");
        assertThat(repository.device(organization, created.id()).block(TIMEOUT))
            .extracting(Device::lastSeen, Device::revision, Device::health)
            .containsExactly(before.lastSeen(), before.revision(), "UNKNOWN");
        assertThat(repository.alerts(organization, created.id(), "", 100).collectList().block(TIMEOUT)).isEmpty();

        Instant newer = now().minusSeconds(10);
        assertThat(project(observation(created.id(), "host", "boot-b", 1, newer, "HEALTHY"))).isTrue();
        assertThat(source(created.id(), "host"))
            .extracting(SourceState::observedAt, SourceState::epoch, SourceState::freshness)
            .containsExactly(newer, "boot-b", "FRESH");
        assertThat(repository.device(organization, created.id()).block(TIMEOUT).revision())
            .isEqualTo(before.revision() + 1);
    }

    @Test
    void currentHealthyHostDoesNotRefreshOrHideHistoricalCriticalBmcEvidence() {
        Device created = createDevice();
        Instant bmcTime = now().minusSeconds(600);
        assertThat(project(observation(created.id(), "bmc", "bmc-boot", 1, bmcTime, "CRITICAL"))).isTrue();
        assertThat(project(observation(created.id(), "host", "host-boot", 1, now(), "HEALTHY"))).isTrue();
        assertThat(repository.device(organization, created.id()).block(TIMEOUT).health()).isEqualTo("HEALTHY");
        assertThat(source(created.id(), "bmc"))
            .extracting(SourceState::observedAt, SourceState::freshness, SourceState::health)
            .containsExactly(bmcTime, "STALE", "CRITICAL");
        assertThat(source(created.id(), "host").freshness()).isEqualTo("FRESH");
        assertThat(repository.alerts(organization, created.id(), "OPEN", 100).collectList().block(TIMEOUT))
            .singleElement().extracting(Alert::severity).isEqualTo("CRITICAL");
    }

    @Test void staleSuccessfulSourceWithRecentConnectionFailureIsOfflineInEveryInventoryRead(){
        Device created=createDevice();Instant at=now().minusSeconds(86400);
        project(observation(created.id(),"ssh","old",1,at,"HEALTHY"));
        db.sql("INSERT INTO device_connection(organization_id,device_id,slot,revision,enabled,settings,ciphertext,status,last_attempt_at,last_success_at,next_poll_at,error_code,source_epoch) VALUES(:org,:device,'ssh',1,1,'{}','test','ERROR',UTC_TIMESTAMP(6),:at,UTC_TIMESTAMP(6),'SSH_TIMEOUT','test')")
            .bind("org",organization).bind("device",created.id()).bind("at",LocalDateTime.ofInstant(at,ZoneOffset.UTC)).fetch().rowsUpdated().block(TIMEOUT);
        assertThat(repository.device(organization,created.id()).block(TIMEOUT)).extracting(Device::health,Device::availability,Device::lastSeen).containsExactly("UNKNOWN","OFFLINE",at);
        assertThat(repository.deviceBatch(organization,java.util.List.of(created.id())).single().block(TIMEOUT).availability()).isEqualTo("OFFLINE");
        assertThat(repository.devices(organization,10,"","","","","HEALTHY").collectList().block(TIMEOUT)).isEmpty();
        assertThat(repository.devices(organization,10,"","","","","UNKNOWN").collectList().block(TIMEOUT)).hasSize(1);
        assertThat(repository.overview(organization).block(TIMEOUT)).extracting(Overview::healthy,Overview::unknown,Overview::stale).containsExactly(0L,1L,1L);
        db.sql("UPDATE device_connection SET status='RUNNING' WHERE organization_id=:org AND device_id=:device").bind("org",organization).bind("device",created.id()).fetch().rowsUpdated().block(TIMEOUT);
        assertThat(repository.device(organization,created.id()).block(TIMEOUT).availability()).isEqualTo("OFFLINE");
        project(observation(created.id(),"ssh","old",2,now(),"HEALTHY"));
        assertThat(repository.device(organization,created.id()).block(TIMEOUT)).extracting(Device::health,Device::availability).containsExactly("HEALTHY","ONLINE");
    }

    @Test
    void acknowledgmentUsesTenantAndRevisionAndRollsBackWhenAuditFails() {
        Device created = createDevice();
        project(observation(created.id(), "host", "boot-a", 1, now().minusSeconds(10), "WARNING"));
        Alert opened = repository.alerts(organization, created.id(), "OPEN", 10).single().block(TIMEOUT);
        long outboxBefore = count("outbox", organization);
        long auditBefore = count("control_audit", organization);
        assertThatThrownBy(() -> repository.acknowledge(otherOrganization, "actor", opened.id(), opened.revision()).block(TIMEOUT))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> repository.acknowledge(organization, "x".repeat(121), opened.id(), opened.revision()).block(TIMEOUT))
            .isInstanceOf(RuntimeException.class);
        assertThat(repository.alerts(organization, created.id(), "OPEN", 10).single().block(TIMEOUT).revision())
            .isEqualTo(opened.revision());
        assertThat(count("outbox", organization)).isEqualTo(outboxBefore);
        assertThat(count("control_audit", organization)).isEqualTo(auditBefore);

        Alert acknowledged = repository.acknowledge(organization, "operator", opened.id(), opened.revision()).block(TIMEOUT);
        assertThat(acknowledged).extracting(Alert::state, Alert::revision, Alert::acknowledgedBy)
            .containsExactly("ACKNOWLEDGED", opened.revision() + 1, "operator");
        assertThat(acknowledged.acknowledgedAt()).isNotNull();
        assertThatThrownBy(() -> repository.acknowledge(organization, "operator", opened.id(), opened.revision()).block(TIMEOUT))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(HttpStatus.CONFLICT));
        assertThat(count("outbox", organization)).isEqualTo(outboxBefore + 1);
        assertThat(count("control_audit", organization)).isEqualTo(auditBefore + 1);
        assertThat(count("outbox", otherOrganization)).isZero();
    }

    @Test
    void criticalEscalationReopensAcknowledgedWarningWithNewRevision() {
        Device created = createDevice();
        Instant start = now().minusSeconds(20);
        project(observation(created.id(), "host", "boot-a", 1, start, "WARNING"));
        Alert warning = repository.alerts(organization, created.id(), "", 10).single().block(TIMEOUT);
        Alert acknowledged = repository.acknowledge(organization, "operator", warning.id(), warning.revision()).block(TIMEOUT);
        project(observation(created.id(), "host", "boot-a", 2, start.plusSeconds(1), "CRITICAL"));
        Alert critical = repository.alerts(organization, created.id(), "", 10).single().block(TIMEOUT);
        assertThat(critical).extracting(Alert::id, Alert::state, Alert::severity, Alert::revision)
            .containsExactly(warning.id(), "OPEN", "CRITICAL", acknowledged.revision() + 1);
        assertThat(critical.title()).contains("CRITICAL");
        assertThat(critical.acknowledgedAt()).isNull();
        assertThat(critical.acknowledgedBy()).isNull();
        assertThatThrownBy(() -> repository.acknowledge(organization, "operator", critical.id(), acknowledged.revision()).block(TIMEOUT))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test void freshRecoveryResolvesSourceAlertAndRecurrenceReopensWithSpecificReason(){
        Device created=createDevice();Instant at=now().minusSeconds(30);
        project(new Observation("warning",created.id(),"network","DeviceSummaryObserved","epoch",1,at,"WARNING",Map.of(),"PSU 2 reports failure"));
        Alert warning=repository.alerts(organization,created.id(),"OPEN",10).single().block(TIMEOUT);
        assertThat(warning.title()).contains("PSU 2 reports failure");
        project(observation(created.id(),"network","epoch",2,at.plusSeconds(1),"HEALTHY"));
        assertThat(repository.alerts(organization,created.id(),"RESOLVED",10).single().block(TIMEOUT).revision()).isEqualTo(warning.revision()+1);
        project(new Observation("recurrence",created.id(),"network","DeviceSummaryObserved","epoch",3,at.plusSeconds(2),"WARNING",Map.of(),"Fan tray 1 down"));
        assertThat(repository.alerts(organization,created.id(),"OPEN",10).single().block(TIMEOUT)).satisfies(alert->{assertThat(alert.title()).contains("Fan tray 1 down");assertThat(alert.openedAt()).isEqualTo(at.plusSeconds(2));});
    }

    @Test
    void concurrentRevisionAllocationIsMonotonicUniqueAndTenantScoped() {
        RollupKey key = key(organization);
        var revisions = Flux.range(0, 12).flatMap(ignored -> rollups.nextRevision(key), 4)
            .collectSortedList().block(TIMEOUT);
        assertThat(revisions).containsExactlyElementsOf(LongStream.rangeClosed(1, 12).boxed().toList());
        assertThat(rollups.nextRevision(key).block(TIMEOUT)).isEqualTo(13);
        assertThat(rollups.nextRevision(key(otherOrganization)).block(TIMEOUT)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void newerPartialCorrectionRevokesOverlappingCompleteProofAndOlderCheckpointCannotRestoreIt(boolean completePrefix) {
        RollupKey key = key(organization);
        Instant from = Instant.parse("2026-09-01T10:00:00Z");
        Instant through = from.plusSeconds(3600);
        long originalRevision = rollups.nextRevision(key).block(TIMEOUT);
        rollups.checkpoint(key, from, through, through, originalRevision, 1).block(TIMEOUT);
        assertThat(progress(key)).extracting(Progress::completeFrom, Progress::completeThrough, Progress::revision)
            .containsExactly(from, through, originalRevision);

        Instant correctionFrom = from.plusSeconds(900);
        Instant correctionThrough = from.plusSeconds(1800);
        Instant prefixThrough = completePrefix ? from.plusSeconds(1200) : null;
        long correctionRevision = rollups.nextRevision(key).block(TIMEOUT);
        rollups.checkpoint(key, correctionFrom, correctionThrough, prefixThrough, correctionRevision, 0.5).block(TIMEOUT);
        Progress corrected = progress(key);
        assertThat(corrected.revision()).isEqualTo(correctionRevision);
        assertThat(corrected.processedFrom()).isEqualTo(correctionFrom);
        assertThat(corrected.processedThrough()).isEqualTo(correctionThrough);
        assertThat(corrected.coverage()).isEqualTo(0.5);
        if (completePrefix) {
            assertThat(corrected.completeThrough() == null || !corrected.completeThrough().isAfter(prefixThrough))
                .as("a partial tail must revoke the older completeness proof through 11:00").isTrue();
        } else {
            assertThat(corrected.completeFrom()).isNull();
            assertThat(corrected.completeThrough()).isNull();
        }
        rollups.checkpoint(key, from, through, through, originalRevision, 1).block(TIMEOUT);
        assertThat(progress(key)).isEqualTo(corrected);
    }

    private CreateDevice deviceInput() {
        return new CreateDevice("Integration device", "HOST", "site-a", "Test", "Test", "192.0.2.10");
    }

    private Device createDevice() {
        return repository.create(organization, "operator", deviceInput()).block(TIMEOUT);
    }

    private Observation observation(String device, String source, String epoch, long sequence, Instant at, String health) {
        return new Observation(UUID.randomUUID().toString(), device, source, "DeviceSummaryObserved", epoch,
            sequence, at, health, Map.of("cpu_percent", 30.0), "Integration observation");
    }

    private boolean project(Observation observation) {
        return Boolean.TRUE.equals(repository.project(organization, observation).block(TIMEOUT));
    }

    private SourceState source(String device, String source) {
        return repository.sources(organization, device).filter(item -> item.sourceId().equals(source)).single().block(TIMEOUT);
    }

    private long count(String table, String org) {
        return db.sql("SELECT COUNT(*) n FROM " + table + " WHERE organization_id=:org").bind("org", org)
            .map((row, metadata) -> ((Number) row.get("n")).longValue()).one().block(TIMEOUT);
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.SECONDS); }

    private RollupKey key(String org) { return new RollupKey(org, "device", "interface", "primary", "rx"); }

    private record Progress(Instant processedFrom, Instant processedThrough, Instant completeFrom,
                            Instant completeThrough, long revision, double coverage) {}

    private Progress progress(RollupKey key) {
        return db.sql("SELECT * FROM rollup_progress WHERE organization_id=:org AND device_id=:device " +
                "AND interface_id=:interface AND source_id=:source AND direction=:direction")
            .bind("org", key.organizationId()).bind("device", key.deviceId()).bind("interface", key.interfaceId())
            .bind("source", key.sourceId()).bind("direction", key.direction())
            .map((row, metadata) -> new Progress(instant(row.get("processed_from", LocalDateTime.class)),
                instant(row.get("processed_through", LocalDateTime.class)), instant(row.get("complete_from", LocalDateTime.class)),
                instant(row.get("complete_through", LocalDateTime.class)), row.get("last_applied_revision", Long.class),
                row.get("coverage", Double.class))).one().block(TIMEOUT);
    }

    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
}
