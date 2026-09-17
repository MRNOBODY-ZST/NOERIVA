package io.noeriva.control;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static io.noeriva.control.Models.*;

class ProjectionPolicyTest {
    private final Instant now = Instant.parse("2026-09-06T08:00:00Z");
    private Observation observation(String epoch, long sequence, Instant at) {
        return new Observation("event-1", "device-1", "host", "DeviceSummaryObserved", epoch, sequence, at, "HEALTHY", Map.of("cpu_percent", 30.0), "summary");
    }
    @Test void duplicateAndOldSequenceNeverRefreshFreshness() {
        var old = observation("boot-a", 100, now.minusSeconds(20));
        assertThat(ProjectionPolicy.accepts(old, observation("boot-a", 100, now), now)).isFalse();
        assertThat(ProjectionPolicy.accepts(old, observation("boot-a", 99, now), now)).isFalse();
        assertThat(ProjectionPolicy.accepts(old, observation("boot-a", 101, now.minusSeconds(10)), now)).isTrue();
    }
    @Test void epochChangeRequiresNewerObservedTimeAndFutureDataIsRejected() {
        var old = observation("boot-a", 100, now.minusSeconds(20));
        assertThat(ProjectionPolicy.accepts(old, observation("boot-b", 1, now.minusSeconds(30)), now)).isFalse();
        assertThat(ProjectionPolicy.accepts(old, observation("boot-b", 1, now), now)).isTrue();
        assertThatThrownBy(() -> ProjectionPolicy.accepts(old, observation("boot-b", 1, now.plusSeconds(121)), now)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void staleSourceRemainsStaleWhenReadAgain() {
        assertThat(ProjectionPolicy.freshness(now.minusSeconds(400), now)).isEqualTo("STALE");
        assertThat(ProjectionPolicy.freshness(null, now)).isEqualTo("MISSING");
    }
    @Test void sinkIncompatibleValuesAreRejectedBeforeDurableAdmission() {
        assertThatThrownBy(()->ProjectionPolicy.accepts(null,observation("a",Long.MAX_VALUE,now),now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ProjectionPolicy.accepts(null,observation("a",1,Instant.EPOCH),now)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void staleCriticalRemainsHistoricalAndDoesNotMasqueradeAsCurrentState() {
        var host=ProjectionPolicy.source(observation("a",1,now),now);
        var bmc=new SourceState("bmc","DeviceSummaryObserved",now.minusSeconds(500),1,"a","CRITICAL",Map.of(),"STALE");
        assertThat(ProjectionPolicy.aggregateHealth(java.util.List.of(host,bmc))).isEqualTo("HEALTHY");
    }
    @Test void oldHealthyReadingAndMissingSourcesNeverProveCurrentHealth(){
        var stale=ProjectionPolicy.source(observation("a",1,now.minusSeconds(181)),now);
        assertThat(ProjectionPolicy.aggregateHealth(java.util.List.of(stale))).isEqualTo("UNKNOWN");
        assertThat(stale.health()).isEqualTo("HEALTHY");
        assertThat(ProjectionPolicy.aggregateHealth(java.util.List.of())).isEqualTo("UNKNOWN");
    }
    @Test void sshObservationsPassValidationProjectionAndWorkspacePagination() {
        var ssh=new Observation("ssh-event", "device-1", "ssh", "DeviceSummaryObserved", "ssh-epoch", 1, now, "HEALTHY", Map.of("temperature_celsius", 42.0), null);
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(ssh)).isEmpty();
        }
        assertThat(ProjectionPolicy.accepts(null,ssh,now)).isTrue();
        var source=new WorkspaceModels.MonitoringSource("device-1","iMana","BMC","default","Default","ssh","DeviceSummaryObserved","HEALTHY",now,"FRESH",ssh.metrics(),1,"ssh-epoch");
        assertThat(WorkspaceModels.SourceCursor.decode(WorkspaceModels.SourceCursor.encode(source)))
            .isEqualTo(new WorkspaceModels.SourceCursor("device-1","ssh"));
        var unknown=new Observation("unknown", "device-1", "shell", "DeviceSummaryObserved", "epoch", 1, now, "HEALTHY", Map.of(), null);
        assertThatThrownBy(()->ProjectionPolicy.accepts(null,unknown,now)).isInstanceOf(IllegalArgumentException.class);
    }
}
