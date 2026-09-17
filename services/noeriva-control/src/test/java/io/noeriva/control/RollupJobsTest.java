package io.noeriva.control;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.noeriva.control.SecurityConfiguration.Operator;

class RollupJobsTest {
    private final Instant now=Instant.parse("2026-09-06T12:30:00Z");
    private RollupJobs.Request request(Instant from,Instant to,String direction) {return new RollupJobs.Request("device-a","eth0",from,to,direction);}
    @Test void oneDayClosedWindowIsAcceptedButFutureMisalignedAndLongWindowsAreRejected() {
        assertDoesNotThrow(()->RollupJobs.validate(request(now.minusSeconds(86400),now,"rx"),now));
        assertThrows(IllegalArgumentException.class,()->RollupJobs.validate(request(now.minusSeconds(86400+300),now,"rx"),now));
        assertThrows(IllegalArgumentException.class,()->RollupJobs.validate(request(now.minusSeconds(300),now.plusSeconds(300),"rx"),now));
        assertThrows(IllegalArgumentException.class,()->RollupJobs.validate(request(now.minusSeconds(299),now,"rx"),now));
        assertThrows(IllegalArgumentException.class,()->RollupJobs.validate(request(now.minusSeconds(300),now,"both"),now));
    }
    @Test void operatorsAndViewersCannotStartAdministrativeRebuilds() {
        assertDoesNotThrow(()->RollupJobs.requireAdmin(new Operator("admin","unused","org-a",List.of("ADMIN"))));
        assertThrows(ApiException.class,()->RollupJobs.requireAdmin(new Operator("operator","unused","org-a",List.of("OPERATOR"))));
        assertThrows(ApiException.class,()->RollupJobs.requireAdmin(new Operator("viewer","unused","org-a",List.of("VIEWER"))));
    }
}
