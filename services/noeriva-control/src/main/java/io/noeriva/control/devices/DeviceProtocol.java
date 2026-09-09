package io.noeriva.control.devices;

import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only protocol boundary. Drivers never persist secrets or mutate a managed device. */
public interface DeviceProtocol {
    String protocol();
    Mono<Reading> read(Target target, Secrets secrets);

    /** address is resolved and checked by TargetPolicy before the driver is called. */
    record Target(String host, String address, int port, String protocol, String username,
                  String snmpVersion, String securityLevel, String authProtocol, String privacyProtocol,
                  String contextName, String tlsMode, String certificateSha256,
                  int timeoutMillis, int maxInterfaces, String sshProfile, String sshHostKeySha256) {
        public Target(String host,String address,int port,String protocol,String username,String snmpVersion,
                      String securityLevel,String authProtocol,String privacyProtocol,String contextName,String tlsMode,
                      String certificateSha256,int timeoutMillis,int maxInterfaces) {
            this(host,address,port,protocol,username,snmpVersion,securityLevel,authProtocol,privacyProtocol,contextName,
                 tlsMode,certificateSha256,timeoutMillis,maxInterfaces,null,null);
        }
    }
    record Secrets(String community, String authPassword, String privacyPassword, String password) {
        @Override public String toString() { return "Secrets[REDACTED]"; }
    }
    record Identity(String vendor, String family, String profileId, String model, String serialNumber,
                    String firmware, String sysObjectId, String sysName, String description) {}
    record Sensor(String id, String label, String metric, String unit, Double value, String health,
                  String sourceRef) {}
    /** Counters are decimal unsigned integers, never JSON floating point. */
    record Port(String key, String name, String macAddress, String speedBps, String adminStatus,
                String operStatus, String inOctets, String outOctets, String discontinuity,
                int counterBits, String sourceRef) {}
    record Reading(Instant observedAt, Identity identity, String health, Map<String,Double> metrics,
                   List<Sensor> sensors, List<Port> ports, List<String> capabilities,
                   List<String> qualityFlags, Map<String,String> facts) {}
    final class Failure extends RuntimeException {
        private final String code;
        public Failure(String code, String safeMessage) { super(safeMessage); this.code=code; }
        public String code() { return code; }
    }
}
