package io.noeriva.control.devices;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.net.ssl.SSLContext;
import java.net.URI;
import java.time.*;
import java.util.*;
import static io.noeriva.control.devices.RedfishHttps.failure;

/** Standard Redfish discovery and bounded sensor reads; OEM numbers are never guessed. */
@Component
public final class RedfishDriver implements DeviceProtocol {
    private final SSLContext systemContext;
    private final Scheduler io=Schedulers.newBoundedElastic(16,16,"noeriva-redfish",60,true);
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().streamReadConstraints(
        StreamReadConstraints.builder().maxNestingDepth(48).maxStringLength(131072).maxNumberLength(128).build()).build()).build();
    public RedfishDriver() { this(defaultContext()); }
    RedfishDriver(SSLContext systemContext) { this.systemContext=systemContext; }
    private static SSLContext defaultContext() { try { return SSLContext.getDefault(); } catch(Exception e) { throw failure("TLS_VALIDATION_FAILED"); } }
    @Override public String protocol() { return "REDFISH"; }
    @Override public Mono<Reading> read(Target target,Secrets secrets) {
        return Mono.defer(() -> {
            RedfishHttps transport=new RedfishHttps(target,secrets,systemContext);
            var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
            return Mono.fromCallable(() -> {
                    try { return new Scan(target,transport).read(); }
                    catch(RuntimeException e) { if(cancelled.get()) return null; throw e; }
                }).subscribeOn(io)
                .doOnCancel(() -> { cancelled.set(true); transport.close(); })
                .timeout(Duration.ofSeconds(25),Mono.error(failure("TIMEOUT")))
                .onErrorMap(e -> !(e instanceof Failure),e -> failure("COLLECTION_FAILED"))
                .doFinally(signal -> transport.close());
        });
    }
    @PreDestroy public void close() { io.dispose(); }
    enum Kind { ROOT, SYSTEMS, SYSTEM, MANAGERS, MANAGER, CHASSIS_COLLECTION, CHASSIS, THERMAL, POWER,
        SENSORS, SENSOR, THERMAL_SUBSYSTEM, THERMAL_METRICS, FANS, FAN, POWER_SUBSYSTEM, POWER_SUPPLIES, POWER_SUPPLY, POWER_SUPPLY_METRICS, ENVIRONMENT }
    record Task(String path,Kind kind) {}
    private static final class Scan {
        final Target target; final RedfishHttps transport;
        final ArrayDeque<Task> pending=new ArrayDeque<>(); final Set<String> scheduled=new HashSet<>();
        final LinkedHashSet<String> capabilities=new LinkedHashSet<>(),quality=new LinkedHashSet<>();
        final Map<String,String> facts=new LinkedHashMap<>(); final Map<String,Double> metrics=new LinkedHashMap<>();
        final List<Sensor> sensors=new ArrayList<>(); final Set<String> sensorSources=new HashSet<>();
        JsonNode system,manager; String health="UNKNOWN"; int requests,systems;
        final Set<String> chassisResources=new HashSet<>(); final Map<String,Double> powerCandidates=new LinkedHashMap<>();
        boolean ambiguousPower; final Instant startedAt=Instant.now();
        Scan(Target target,RedfishHttps transport) { this.target=target; this.transport=transport; }
        Reading read() {
            enqueue("/redfish/v1",Kind.ROOT);
            while(!pending.isEmpty()) {
                if(requests>=80) { partial("REQUEST_LIMIT"); break; }
                Task task=pending.removeFirst(); requests++;
                RedfishHttps.Response response=transport.get(task.path()); int status=response.status();
                if(status==401) throw failure("AUTHENTICATION_FAILED");
                if(status>=300 && status<400) throw failure("REDIRECT_REJECTED");
                if(task.kind()==Kind.ROOT && status==404) throw failure("PROTOCOL_UNSUPPORTED");
                if(task.kind()==Kind.ROOT && status==403) throw failure("AUTHORIZATION_FAILED");
                if(status==403 || status==404) { partial(status==403?"RESOURCE_FORBIDDEN":"RESOURCE_NOT_FOUND"); continue; }
                if(status<200 || status>=300) {
                    if(task.kind()==Kind.ROOT) throw failure(status==429?"RATE_LIMITED":"DEVICE_HTTP_ERROR");
                    partial(status==429?"RESOURCE_RATE_LIMITED":"RESOURCE_HTTP_ERROR"); continue;
                }
                JsonNode n;
                try { n=JSON.readTree(response.body()); if(n==null || !n.isObject()) throw failure("INVALID_JSON_RESPONSE"); }
                catch(Failure e) { throw e; } catch(Exception e) { throw failure("INVALID_JSON_RESPONSE"); }
                parse(task,n);
            }
            if(system==null && manager==null) partial("IDENTITY_UNAVAILABLE");
            if(sensors.isEmpty()) partial("SENSORS_UNAVAILABLE");
            if(systems>1) partial("MULTIPLE_SYSTEMS_FIRST_IDENTITY");
            facts.put("authentication","HTTPS_BASIC"); facts.put("transport","HTTPS"); facts.put("requests",Integer.toString(requests));
            facts.put("readOnly","true"); facts.put("verification","PROTOCOL_READ");
            facts.put("collectionStartedAt",startedAt.toString()); summarize();
            return new Reading(Instant.now(),identity(),health,Map.copyOf(metrics),List.copyOf(sensors),List.of(),List.copyOf(capabilities),List.copyOf(quality),Map.copyOf(facts));
        }
        void parse(Task task,JsonNode n) {
            String path=task.path();
            switch(task.kind()) {
                case ROOT -> {
                    String version=text(n,"RedfishVersion");
                    if(version==null && !Objects.toString(text(n,"@odata.type"),"").contains("ServiceRoot")) throw failure("PROTOCOL_UNSUPPORTED");
                    fact("redfishVersion",version); capabilities.add("redfish");
                    linked(n,"Systems",Kind.SYSTEMS,true); linked(n,"Managers",Kind.MANAGERS,true); linked(n,"Chassis",Kind.CHASSIS_COLLECTION,true);
                }
                case SYSTEMS -> members(n,Kind.SYSTEM,Kind.SYSTEMS);
                case MANAGERS -> members(n,Kind.MANAGER,Kind.MANAGERS);
                case CHASSIS_COLLECTION -> members(n,Kind.CHASSIS,Kind.CHASSIS_COLLECTION);
                case SYSTEM -> {
                    if(text(n,"Id")==null && text(n,"Manufacturer")==null && text(n,"Model")==null) { partial("INVALID_RESOURCE"); break; }
                    systems++; capabilities.add("inventory");
                    if(system==null) {
                        system=n; health=health(n); fact("systemResource",path); fact("biosVersion",text(n,"BIOSVersion")); fact("powerState",text(n,"PowerState"));
                        numberFact("processorCount",n.path("ProcessorSummary").get("Count"));
                        numberFact("memoryGiB",n.path("MemorySummary").get("TotalSystemMemoryGiB"));
                        fact("systemSchema",text(n,"@odata.type"));
                    }
                }
                case MANAGER -> {
                    if(text(n,"Id")==null && text(n,"Manufacturer")==null && text(n,"Model")==null) { partial("INVALID_RESOURCE"); break; }
                    capabilities.add("managerInventory");
                    if(manager==null || (!"BMC".equals(text(manager,"ManagerType")) && "BMC".equals(text(n,"ManagerType")))) {
                        manager=n; fact("managerResource",path); fact("managerModel",text(n,"Model")); fact("managerSchema",text(n,"@odata.type"));
                    }
                }
                case CHASSIS -> {
                    boolean found=false;
                    found|=linked(n,"Thermal",Kind.THERMAL,false); found|=linked(n,"Power",Kind.POWER,false);
                    found|=linked(n,"Sensors",Kind.SENSORS,false); found|=linked(n,"ThermalSubsystem",Kind.THERMAL_SUBSYSTEM,false);
                    found|=linked(n,"PowerSubsystem",Kind.POWER_SUBSYSTEM,false); found|=linked(n,"EnvironmentMetrics",Kind.ENVIRONMENT,false);
                    if(!found) partial("CHASSIS_SENSOR_LINKS_MISSING");
                }
                case THERMAL -> {
                    array(n,"Temperatures",(x,index) -> add(x,"temperature","Cel",x.get("ReadingCelsius"),path+"#/Temperatures/"+index,"Temperature "+index));
                    array(n,"Fans",(x,index) -> {
                        String unit=text(x,"ReadingUnits");
                        if("Percent".equals(unit)) unit="%";
                        if("RPM".equals(unit)||"%".equals(unit)) add(x,"fanSpeed",unit,x.get("Reading"),path+"#/Fans/"+index,"Fan "+index);
                        else if(number(x.get("Reading"))!=null) partial("UNSUPPORTED_UNIT");
                    });
                }
                case POWER -> {
                    JsonNode controls=n.get("PowerControl");
                    if(controls!=null && controls.isArray() && controls.size()>1) ambiguousPower=true;
                    array(n,"PowerControl",(x,index) -> {
                        String source=path+"#/PowerControl/"+index+"/PowerConsumedWatts";
                        add(x,"power","W",x.get("PowerConsumedWatts"),source,"System power");
                        if(controls.size()==1) powerCandidate(source,x.get("PowerConsumedWatts"));
                    });
                    array(n,"Voltages",(x,index) -> add(x,"voltage","V",x.get("ReadingVolts"),path+"#/Voltages/"+index,"Voltage "+index));
                    array(n,"PowerSupplies",(x,index) -> {
                        String ref=path+"#/PowerSupplies/"+index;
                        add(x,"power","W",x.get("PowerInputWatts"),ref+"/PowerInputWatts","PSU input");
                        add(x,"power","W",x.get("PowerOutputWatts"),ref+"/PowerOutputWatts","PSU output");
                        if(number(x.get("PowerOutputWatts"))==null) add(x,"power","W",x.get("LastPowerOutputWatts"),ref+"/LastPowerOutputWatts","PSU last output");
                        addHealth(x,ref,"PSU "+index);
                    });
                }
                case SENSORS -> members(n,Kind.SENSOR,Kind.SENSORS);
                case SENSOR -> {
                    String type=text(n,"ReadingType"),unit=text(n,"ReadingUnits");
                    String metric=switch(Objects.toString(type,"")) { case "Temperature" -> "temperature"; case "Power" -> "power"; case "Voltage" -> "voltage"; case "Current" -> "current"; case "Rotational" -> "fanSpeed"; case "Percent" -> "percent"; case "EnergyJoules" -> "energy"; default -> null; };
                    String expected=metric==null?null:switch(metric) { case "temperature" -> "Cel"; case "power" -> "W"; case "voltage" -> "V"; case "current" -> "A"; case "fanSpeed" -> "RPM"; case "percent" -> "%"; case "energy" -> "J"; default -> null; };
                    if(metric!=null && expected.equals(unit)) add(n,metric,unit,n.get("Reading"),path,"Sensor");
                    else if(number(n.get("Reading"))!=null) partial("UNSUPPORTED_UNIT");
                }
                case THERMAL_SUBSYSTEM -> { linked(n,"Fans",Kind.FANS,false); linked(n,"ThermalMetrics",Kind.THERMAL_METRICS,false); }
                case THERMAL_METRICS -> {
                    array(n,"TemperatureReadingsCelsius",(x,index) -> add(x,"temperature","Cel",x.get("Reading"),path+"#/TemperatureReadingsCelsius/"+index,"Temperature "+index));
                    excerpt(n,"PowerWatts","power","W",path);
                }
                case FANS -> members(n,Kind.FAN,Kind.FANS);
                case FAN -> { excerpt(n,"SpeedPercent","fanSpeed","%",path); excerpt(n,"SecondarySpeedPercent","fanSpeed","%",path); addHealth(n,path,"Fan"); }
                case POWER_SUBSYSTEM -> linked(n,"PowerSupplies",Kind.POWER_SUPPLIES,false);
                case POWER_SUPPLIES -> members(n,Kind.POWER_SUPPLY,Kind.POWER_SUPPLIES);
                case POWER_SUPPLY -> { linked(n,"Metrics",Kind.POWER_SUPPLY_METRICS,false); addHealth(n,path,"PSU"); }
                case POWER_SUPPLY_METRICS -> {
                    excerpt(n,"InputPowerWatts","power","W",path); excerpt(n,"OutputPowerWatts","power","W",path);
                    excerpt(n,"InputCurrentAmps","current","A",path); excerpt(n,"InputVoltage","voltage","V",path);
                }
                case ENVIRONMENT -> {
                    excerpt(n,"PowerWatts","power","W",path); excerpt(n,"TemperatureCelsius","temperature","Cel",path);
                    powerCandidate(path+"#/PowerWatts",n.path("PowerWatts").get("Reading"));
                }
            }
        }
        void members(JsonNode n,Kind member,Kind page) {
            JsonNode values=n.get("Members"); if(values==null || !values.isArray()) { partial("INVALID_COLLECTION"); return; }
            int count=0;
            for(JsonNode value:values) {
                if(count++>=256) { partial("MEMBER_LIMIT"); break; }
                String link=value.isString()?value.asString():text(value,"@odata.id");
                if(value.isString()) partial("LEGACY_STRING_MEMBER_LINK");
                if(link==null) partial("INVALID_RESOURCE_LINK"); else enqueue(link,member);
            }
            String next=text(n,"Members@odata.nextLink"); if(next!=null) enqueue(next,page);
        }
        boolean linked(JsonNode n,String key,Kind kind,boolean required) {
            String link=text(n.path(key),"@odata.id");
            if(link==null) { if(required) partial("ROOT_COLLECTION_MISSING"); return false; }
            enqueue(link,kind); return true;
        }
        void enqueue(String link,Kind kind) {
            String path=safePath(link);
            if(kind==Kind.CHASSIS) chassisResources.add(path);
            if(scheduled.add(path)) { if(scheduled.size()>512) { partial("MEMBER_LIMIT"); return; } pending.addLast(new Task(path,kind)); }
            else if(kind==Kind.SYSTEMS || kind==Kind.MANAGERS || kind==Kind.CHASSIS_COLLECTION || kind==Kind.SENSORS || kind==Kind.FANS || kind==Kind.POWER_SUPPLIES) partial("COLLECTION_CYCLE");
        }
        String safePath(String link) {
            try {
                if(link==null || link.length()>2048 || link.matches(".*[\\s\\\\].*") || link.startsWith("//")) throw failure("UNSAFE_RESOURCE_LINK");
                URI uri=URI.create(link);
                if(uri.getRawUserInfo()!=null || uri.getRawFragment()!=null) throw failure("UNSAFE_RESOURCE_LINK");
                if(uri.isAbsolute() && (!"https".equalsIgnoreCase(uri.getScheme()) || !target.host().equalsIgnoreCase(uri.getHost()) || (uri.getPort()<0?443:uri.getPort())!=target.port())) throw failure("UNSAFE_RESOURCE_LINK");
                String path=uri.getRawPath(),decoded=uri.getPath();
                if(path==null || !(path.equals("/redfish/v1")||path.startsWith("/redfish/v1/")) || !Objects.equals(uri.normalize().getRawPath(),path)) throw failure("UNSAFE_RESOURCE_LINK");
                if(decoded.contains("\\") || decoded.contains("%") || Arrays.stream(decoded.split("/",-1)).anyMatch(s -> s.equals(".")||s.equals("..")) || decoded.chars().anyMatch(c -> c<32||c==127)) throw failure("UNSAFE_RESOURCE_LINK");
                String lower=path.toLowerCase(Locale.ROOT); if(lower.contains("%2f")||lower.contains("%5c")) throw failure("UNSAFE_RESOURCE_LINK");
                String query=uri.getRawQuery(); if(query!=null && query.length()>1024) throw failure("UNSAFE_RESOURCE_LINK");
                return path+(query==null?"":"?"+query);
            } catch(Failure e) { throw e; } catch(Exception e) { throw failure("UNSAFE_RESOURCE_LINK"); }
        }
        void excerpt(JsonNode parent,String key,String metric,String unit,String path) {
            JsonNode n=parent.path(key); add(n,metric,unit,n.get("Reading"),path+"#/"+key,key);
        }
        void add(JsonNode n,String metric,String unit,JsonNode reading,String ref,String fallback) {
            boolean inspur=Objects.toString(first(text(system,"Manufacturer"),text(manager,"Manufacturer")),"").toLowerCase(Locale.ROOT).contains("inspur");
            String state=Objects.toString(text(n.path("Status"),"State"),"").toUpperCase(Locale.ROOT);
            if(Set.of("ABSENT","DISABLED","UNAVAILABLEOFFLINE").contains(state)||inspur&&state.equals("DISABLE")) { partial("READING_UNAVAILABLE"); return; }
            Double value=number(reading);
            // Observed SA5212M5 4.26.6 returns fan RPM as decimal strings. Keep this explicit and bounded.
            if(value==null && inspur && reading!=null && reading.isString()) {
                String raw=reading.asString();
                if(raw.length()<=24 && raw.matches("-?(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,6})?")) {
                    value=Double.valueOf(raw);partial("INSPUR_NUMERIC_STRING");
                }
            }
            if(value==null) { if(reading!=null) partial(reading.isNull()?"READING_UNAVAILABLE":"INVALID_READING"); return; }
            if(!sensorSources.add(ref)) return;
            if(sensors.size()>=128) { partial("SENSOR_LIMIT"); return; }
            String label=first(text(n,"Name"),text(n,"FanName"),text(n,"DeviceName"),fallback);
            sensors.add(new Sensor(ref,label,metric,unit,value,health(n),ref)); capabilities.add(metric);
        }
        void addHealth(JsonNode n,String ref,String fallback) {
            String h=health(n); if("UNKNOWN".equals(h)) return;
            String source=ref+"#/Status/Health";
            if(!sensorSources.add(source)) return; if(sensors.size()>=128) { partial("SENSOR_LIMIT"); return; }
            sensors.add(new Sensor(source,first(text(n,"Name"),fallback),"componentHealth","state",null,h,source)); capabilities.add("componentHealth");
        }
        void powerCandidate(String source,JsonNode reading) {
            Double value=number(reading);
            if(value!=null && value>=0 && sensors.stream().anyMatch(sensor -> source.equals(sensor.sourceRef()))) powerCandidates.put(source,value);
        }
        void summarize() {
            sensors.stream().filter(x -> "temperature".equals(x.metric()) && x.value()!=null)
                .max(Comparator.comparingDouble(Sensor::value)).ifPresent(x -> {
                    metrics.put("temperature_celsius",x.value());
                    facts.put("temperatureSummary","maximum_of_collected_temperature_sensors"); facts.put("temperatureSource",x.sourceRef());
                });
            boolean incomplete=quality.stream().anyMatch(x -> Set.of("REQUEST_LIMIT","MEMBER_LIMIT","COLLECTION_CYCLE","INVALID_COLLECTION","RESOURCE_NOT_FOUND","RESOURCE_FORBIDDEN","ROOT_COLLECTION_MISSING").contains(x));
            if(chassisResources.size()==1 && systems==1 && powerCandidates.size()==1 && !ambiguousPower && !incomplete) {
                var power=powerCandidates.entrySet().iterator().next(); metrics.put("power_watts",power.getValue());
                facts.put("powerSummary","unique_chassis_total"); facts.put("powerSource",power.getKey());
            } else facts.put("powerSummary","ambiguous_or_unavailable");
        }
        Identity identity() {
            String manufacturer=first(text(system,"Manufacturer"),text(manager,"Manufacturer"),"Unknown");
            String model=first(text(system,"Model"),text(manager,"Model"),"Unknown");
            String managerModel=Objects.toString(text(manager,"Model"),"");
            String hint=manufacturer.toLowerCase(Locale.ROOT),vendor="GENERIC",family="REDFISH",profile="redfish-generic";
            if(hint.contains("dell")) { vendor="DELL"; family=managerModel.toLowerCase(Locale.ROOT).contains("idrac")?"IDRAC":"REDFISH"; profile="dell-idrac"; }
            else if(hint.contains("huawei")) { vendor="HUAWEI"; family=managerModel.toLowerCase(Locale.ROOT).contains("ibmc")?"IBMC":"REDFISH"; profile="huawei-ibmc"; }
            else if(hint.contains("inspur")) { vendor="INSPUR"; family="BMC"; profile="inspur-bmc"; }
            else if(hint.contains("h3c")||hint.contains("new h3c")) { vendor="H3C"; family=managerModel.toLowerCase(Locale.ROOT).contains("hdm")?"HDM":"REDFISH"; profile="h3c-hdm"; }
            fact("manufacturer",manufacturer);
            return new Identity(vendor,family,profile,model,first(text(system,"SerialNumber"),text(manager,"SerialNumber")),text(manager,"FirmwareVersion"),null,first(text(system,"HostName"),text(system,"Name")),"Redfish standard resources");
        }
        void partial(String flag) { quality.add("PARTIAL"); if(quality.size()<32) quality.add(flag); }
        void fact(String key,String value) { if(value!=null) facts.put(key,value); }
        void numberFact(String key,JsonNode value) { Double number=number(value); if(number!=null) facts.put(key,number.toString()); }
        interface ArrayItem { void accept(JsonNode n,int index); }
        void array(JsonNode n,String key,ArrayItem action) {
            JsonNode values=n.get(key); if(values==null || !values.isArray()) return;
            int i=0; for(JsonNode value:values) { if(i>=256) { partial("MEMBER_LIMIT"); break; } if(value.isObject()) action.accept(value,i); i++; }
        }
    }
    private static Double number(JsonNode n) { if(n==null || !n.isNumber()) return null; double value=n.doubleValue(); return Double.isFinite(value)?value:null; }
    private static String text(JsonNode n,String key) {
        if(n==null || !n.isObject()) return null; JsonNode value=n.get(key); if(value==null || !value.isString()) return null;
        String text=value.asString(); if(text.isBlank()) return null; return text.length()>512?text.substring(0,512):text;
    }
    private static String first(String... values) { for(String value:values) if(value!=null && !value.isBlank()) return value; return null; }
    private static String health(JsonNode n) {
        if(n==null) return "UNKNOWN";
        String raw=first(text(n.path("Status"),"HealthRollup"),text(n.path("Status"),"Health"));
        return switch(Objects.toString(raw,"")) { case "OK" -> "HEALTHY"; case "Warning" -> "WARNING"; case "Critical" -> "CRITICAL"; default -> "UNKNOWN"; };
    }
}
