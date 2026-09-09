package io.noeriva.control;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import java.time.*;
import java.util.List;
import static io.noeriva.control.Models.*;
import static io.noeriva.control.SecurityConfiguration.Operator;
import static org.assertj.core.api.Assertions.*;

class WorkspaceHistoryFailureTest {
    private static final Operator USER=new Operator("viewer","unused","demo",List.of("VIEWER"));
    @Test void unavailableHistoryDoesNotInventEmptySuccessfulOverviewOrSearch(){
        var inventory=new DemoRepository(){@Override public Flux<Event> events(String org,String device,Instant from,Instant to,String cursor,int limit){return Flux.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"HISTORY_UNAVAILABLE","Test provider unavailable"));}};
        var controller=new WorkspaceController(new DemoWorkspaceReadRepository(inventory),inventory);
        var overview=controller.overview(USER,"").block(Duration.ofSeconds(3));
        assertThat(overview.totals().devices()).isEqualTo(5);assertThat(overview.recentEventsStatus()).isEqualTo("UNAVAILABLE");assertThat(overview.qualityFlags()).contains("HISTORY_UNAVAILABLE");
        var search=controller.search(USER,"compute",10).block(Duration.ofSeconds(3));
        assertThat(search.assets()).isNotEmpty();assertThat(search.recentEventsStatus()).isEqualTo("UNAVAILABLE");assertThat(search.qualityFlags()).contains("HISTORY_UNAVAILABLE");
    }
    @Test void missingEventTextDoesNotBecomeASearchableLiteralNull(){
        var inventory=new DemoRepository();
        inventory.project("demo",new Observation("empty-message-event","compute-07","host","DeviceSummaryObserved","next-epoch",2,Instant.now(),"HEALTHY",java.util.Map.of(),null)).block();
        var controller=new WorkspaceController(new DemoWorkspaceReadRepository(inventory),inventory);
        assertThat(controller.search(USER,"null",10).block().recentEvents()).isEmpty();
    }
    @Test void neverObservedAssetsAreNotCountedAsStaleInWorkspaceTotalsOrSites(){
        var inventory=new DemoRepository();var workspace=new DemoWorkspaceReadRepository(inventory);
        inventory.create("demo","admin",new CreateDevice("never-observed","HOST","lab-a","Test","Test","192.0.2.88")).block();
        Instant asOf=Instant.now();
        var totals=workspace.totals("demo","",asOf).block();
        // The unobserved asset and the expired BMC are both UNKNOWN today;
        // only the previously observed BMC counts as STALE.
        assertThat(totals.unknown()).isEqualTo(2);assertThat(totals.stale()).isEqualTo(1);
        var site=workspace.siteHealth("demo","lab-a",asOf).blockFirst();
        assertThat(site.unknown()).isEqualTo(2);assertThat(site.stale()).isEqualTo(1);
        assertThat(inventory.device("demo","bmc-compute-07").block().health()).isEqualTo("UNKNOWN");
        assertThat(inventory.sources("demo","bmc-compute-07").blockFirst().health()).isEqualTo("WARNING");
        // All overview pages share the missing-versus-stale distinction.
        assertThat(inventory.overview("demo").block().stale()).isEqualTo(1);
    }
    @Test void unrelatedOrganizationCannotSeeSyntheticRows(){
        var inventory=new DemoRepository();var workspace=new DemoWorkspaceReadRepository(inventory);var now=Instant.now();
        assertThat(workspace.monitoring("other",100,new WorkspaceModels.SourceCursor("",""),"","","","",now).collectList().block()).isEmpty();
        assertThat(workspace.interfaces("other",100,"","","","",now).collectList().block()).isEmpty();
        assertThat(workspace.siteHealth("other","",now).collectList().block()).isEmpty();
        assertThat(workspace.totals("other","",now).block().devices()).isZero();
    }
}
