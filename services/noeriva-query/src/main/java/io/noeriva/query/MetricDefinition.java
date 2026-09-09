package io.noeriva.query;
import java.util.Map;
public record MetricDefinition(String id,String series,String unit) {
    private static final Map<String,MetricDefinition> CATALOG=Map.of(
        "cpu_percent",new MetricDefinition("cpu_percent","noeriva_cpu_percent","%"),
        "memory_percent",new MetricDefinition("memory_percent","noeriva_memory_percent","%"),
        "temperature_celsius",new MetricDefinition("temperature_celsius","noeriva_temperature_celsius","°C"),
        "power_watts",new MetricDefinition("power_watts","noeriva_power_watts","W"),
        "bandwidth_rx_bps",new MetricDefinition("bandwidth_rx_bps","noeriva_bandwidth_rx_bps","bps"),
        "bandwidth_tx_bps",new MetricDefinition("bandwidth_tx_bps","noeriva_bandwidth_tx_bps","bps"));
    public static MetricDefinition require(String id) {
        var definition=CATALOG.get(id);
        if(definition==null) throw new IllegalArgumentException("Unsupported named metric");
        return definition;
    }
}
