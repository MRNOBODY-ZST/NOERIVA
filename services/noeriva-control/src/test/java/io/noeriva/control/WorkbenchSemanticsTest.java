package io.noeriva.control;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.noeriva.control.WorkbenchModels.*;
import static org.assertj.core.api.Assertions.*;

class WorkbenchSemanticsTest {
    static final Instant AT=Instant.parse("2026-09-05T02:04:20Z");
    static InvestigationInput query(){return new InvestigationInput("198.51.100.26",54021,"TCP",AT,"PUBLIC_TO_PRIVATE");}
    static NetworkEvidence record(String id,String kind,String device,int uncertainty,String lifecycle,String provenance){return new NetworkEvidence(id,device,"site-a",kind,"192.0.2.7",kind.equals("NAT")?50000:null,kind.equals("NAT")?"198.51.100.26":null,kind.equals("NAT")?54021:null,kind.equals("NAT")?"TCP":null,AT.minusSeconds(10),AT.plusSeconds(10),lifecycle,uncertainty,"fixture",provenance,AT);}
    @Test void clockUncertaintyAndCompetingAddressClaimsAreAmbiguous(){
        var uncertain=record("nat","NAT","host-a",2000,"COMPLETE","SYNTHETIC");
        var lease=record("lease","ADDRESS_LEASE","host-a",0,"COMPLETE","SYNTHETIC");
        assertThat(WorkbenchService.correlate("query",query(),List.of(uncertain),List.of(lease),"CONNECTED").status()).isEqualTo("AMBIGUOUS");
        var nat=record("nat","NAT","host-a",0,"COMPLETE","SYNTHETIC");
        var conflict=record("lease-other","ADDRESS_LEASE","host-b",0,"COMPLETE","SYNTHETIC");
        var result=WorkbenchService.correlate("query",query(),List.of(nat),List.of(lease,conflict),"CONNECTED");
        assertThat(result.status()).isEqualTo("AMBIGUOUS");assertThat(result.candidates().getFirst().leases()).hasSize(2);
    }
    @Test void syntheticAndManualEvidenceNeverCombineIntoAConfirmedMapping(){
        var nat=record("nat","NAT","host-a",0,"COMPLETE","MANUAL");
        var synthetic=record("lease","ADDRESS_LEASE","host-a",0,"COMPLETE","SYNTHETIC");
        assertThat(WorkbenchService.correlate("query",query(),List.of(nat),List.of(synthetic),"CONNECTED").status()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }
    @Test void snapshotOnlyOrMissingLeaseCannotConfirmAndUpperIntervalBoundaryIsExclusive(){
        var nat=record("nat","NAT","host-a",0,"SNAPSHOT_ONLY","SYNTHETIC");
        var lease=record("lease","ADDRESS_LEASE","host-a",0,"COMPLETE","SYNTHETIC");
        assertThat(WorkbenchService.correlate("query",query(),List.of(nat),List.of(lease),"DEMO").status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(WorkbenchService.possibleAt(nat,nat.validTo())).isFalse();
        assertThat(WorkbenchService.possibleAt(nat,nat.validFrom())).isTrue();
    }
    @Test void excessCandidatesFailRatherThanReturningAFalseSingleMatch(){
        var candidates=java.util.stream.IntStream.range(0,101).mapToObj(i->record("nat-"+i,"NAT","host-a",0,"COMPLETE","SYNTHETIC")).toList();
        assertThatThrownBy(()->WorkbenchService.correlate("query",query(),candidates,List.of(),"CONNECTED")).isInstanceOf(ApiException.class);
    }
    @Test void literalParsingCanonicalizesIpv6AndNeverAcceptsNamesOrZones(){
        assertThat(WorkbenchService.ip("2001:db8::1")).isEqualTo(WorkbenchService.ip("2001:0db8:0:0:0:0:0:1"));
        assertThatThrownBy(()->WorkbenchService.ip("localhost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->WorkbenchService.ip("fe80::1%en0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->WorkbenchService.ip("256.0.0.1")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void configRedactsWholePrivateKeyBlocksAndRejectsUnboundedDiffInputs(){
        var value=WorkbenchService.redact("hostname test\n-----BEGIN RSA PRIVATE KEY-----\nprivate-material\n-----END RSA PRIVATE KEY-----\napi_key: rawsecret\ninterface eth0");
        assertThat(value.content()).doesNotContain("private-material","rawsecret").contains("hostname test","interface eth0");
        assertThat(value.count()).isEqualTo(4);
        assertThatThrownBy(()->WorkbenchService.redact("line\n".repeat(2001))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void compositeCredentialKeysAreRedactedBeforeStorage(){
        var value=WorkbenchService.redact("PrivateKey = opaqueA\nrocommunity opaqueB\nauthentication-key opaqueC\nSecretAccessKey=opaqueD\nlogging host 192.0.2.1");
        assertThat(value.content()).doesNotContain("opaqueA","opaqueB","opaqueC","opaqueD").contains("logging host 192.0.2.1");
        assertThat(value.count()).isEqualTo(4);
    }
}
