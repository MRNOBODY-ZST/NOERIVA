package io.noeriva.control.devices;

import org.snmp4j.*;
import org.snmp4j.event.*;
import org.snmp4j.mp.*;
import org.snmp4j.security.*;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import java.net.InetAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One isolated UDP/USM session per read; no global credentials or engine cache. */
final class SnmpSession implements AutoCloseable {
    static final int VARIABLE_BUDGET=2500;
    private final DeviceProtocol.Target config;
    private final long deadline;
    private final Snmp snmp;
    private final org.snmp4j.Target<UdpAddress> target;
    private final USM usm;
    private final Set<String> flags;
    private final Set<String> rejectedOptionalOids=new LinkedHashSet<>();
    private volatile boolean closed;
    private int variables;
    private int issues;
    private long auxiliaryDeadline=Long.MAX_VALUE;
    private int auxiliaryVariableCeiling=VARIABLE_BUDGET;
    private boolean auxiliary;
    int issues(){return issues;}
    int remainingVariables(){return Math.max(0,Math.min(VARIABLE_BUDGET,auxiliaryVariableCeiling)-variables);}
    long remainingMillis(){return Math.max(0,Duration.ofNanos(deadline-System.nanoTime()).toMillis());}
    /** Optional endpoint tables share the hard session cap and leave time to publish telemetry. */
    AutoCloseable auxiliaryBudget(long millis,int maxVariables){
        if(auxiliary||millis<1||maxVariables<1)throw new IllegalArgumentException("Invalid auxiliary SNMP budget");
        auxiliary=true;auxiliaryDeadline=Math.min(deadline-Duration.ofMillis(500).toNanos(),System.nanoTime()+Duration.ofMillis(millis).toNanos());
        auxiliaryVariableCeiling=Math.min(VARIABLE_BUDGET,variables+maxVariables);
        return ()->{auxiliary=false;auxiliaryDeadline=Long.MAX_VALUE;auxiliaryVariableCeiling=VARIABLE_BUDGET;};
    }
    private String budgetCode(){return auxiliary?"SNMP_ENDPOINT_BUDGET":"SNMP_VARIABLE_BUDGET";}
    private void mark(String flag){issues++;flags.add(flag);}

    SnmpSession(DeviceProtocol.Target config, DeviceProtocol.Secrets secrets, long deadline, Set<String> flags) throws Exception {
        this.config=config;this.deadline=deadline;this.flags=flags;
        if(config.maxInterfaces()<1 || config.maxInterfaces()>256 || config.port()<1 || config.port()>65535)
            throw failure("SNMP_INVALID_TARGET","SNMP target limits are invalid.");
        boolean v3="3".equals(config.snmpVersion());
        if(!v3 && !"2c".equals(config.snmpVersion()))throw failure("SNMP_UNSUPPORTED_VERSION","Only explicitly configured SNMP v2c and v3 are supported.");
        var security=new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
        security.addAuthenticationProtocol(new AuthSHA());security.addAuthenticationProtocol(new AuthMD5());
        security.addAuthenticationProtocol(new AuthHMAC192SHA256());security.addAuthenticationProtocol(new AuthHMAC384SHA512());
        security.addPrivacyProtocol(new PrivAES128());security.addPrivacyProtocol(new PrivDES());
        byte[] localEngine=MPv3.createLocalEngineID();
        var counters=new CounterSupport();
        usm=new USM(security,new OctetString(localEngine),0,counters);
        // address was checked by TargetPolicy. ofLiteral cannot perform a DNS lookup.
        var address=new UdpAddress(InetAddress.ofLiteral(config.address()),config.port());
        if(v3){
            int level=switch(blank(config.securityLevel())){
                case "noAuthNoPriv" -> SecurityLevel.NOAUTH_NOPRIV;
                case "authNoPriv" -> SecurityLevel.AUTH_NOPRIV;
                case "authPriv" -> SecurityLevel.AUTH_PRIV;
                default -> throw failure("SNMP_UNSUPPORTED_SECURITY","Select an explicit SNMP v3 security level.");
            };
            if(blank(config.username()).isEmpty())throw failure("SNMP_INVALID_CREDENTIAL","SNMP v3 requires a security name.");
            OID auth=null,privacy=null;
            if(level>=SecurityLevel.AUTH_NOPRIV){
                auth=switch(blank(config.authProtocol())){
                    case "SHA1" -> AuthSHA.ID;case "MD5" -> AuthMD5.ID;
                    case "SHA256" -> AuthHMAC192SHA256.ID;case "SHA512" -> AuthHMAC384SHA512.ID;
                    default -> throw failure("SNMP_UNSUPPORTED_SECURITY","The requested SNMP authentication algorithm is unsupported.");
                };
                requirePassword(secrets.authPassword());
            } else if(!blank(config.authProtocol()).isEmpty() && !"NONE".equals(config.authProtocol()))
                throw failure("SNMP_UNSUPPORTED_SECURITY","An authentication algorithm contradicts noAuthNoPriv.");
            if(level==SecurityLevel.AUTH_PRIV){
                privacy=switch(blank(config.privacyProtocol())){
                    case "AES128" -> PrivAES128.ID;case "DES" -> PrivDES.ID;
                    default -> throw failure("SNMP_UNSUPPORTED_SECURITY","The requested SNMP privacy algorithm is unsupported; AES256 key-extension variants are not inferred.");
                };
                requirePassword(secrets.privacyPassword());
            } else if(!blank(config.privacyProtocol()).isEmpty() && !"NONE".equals(config.privacyProtocol()))
                throw failure("SNMP_UNSUPPORTED_SECURITY","A privacy algorithm contradicts the selected security level.");
            usm.addUser(new UsmUser(new OctetString(config.username()),auth,auth==null?null:new OctetString(secrets.authPassword()),
                privacy,privacy==null?null:new OctetString(secrets.privacyPassword())));
            var user=new UserTarget<UdpAddress>();
            user.setAddress(address);user.setSecurityName(new OctetString(config.username()));
            user.setSecurityLevel(level);user.setVersion(SnmpConstants.version3);
            target=user;
        }else{
            if(blank(secrets.community()).isEmpty())throw failure("SNMP_INVALID_CREDENTIAL","SNMP v2c requires a community.");
            var community=new CommunityTarget<UdpAddress>();community.setAddress(address);
            community.setVersion(SnmpConstants.version2c);community.setCommunity(new OctetString(secrets.community()));target=community;
        }
        target.setRetries(0);target.setTimeout(Math.clamp(config.timeoutMillis(),100,5000));
        target.setMaxSizeRequestPDU(65507);
        var dispatcher=new MessageDispatcherImpl();dispatcher.addMessageProcessingModel(new MPv2c());
        dispatcher.addMessageProcessingModel(new MPv3(localEngine,null,security,SecurityModels.getCollection(new SecurityModel[]{usm}),counters));
        var transport=new DefaultUdpTransportMapping(new UdpAddress(InetAddress.ofLiteral(address.getInetAddress() instanceof java.net.Inet6Address?"::":"0.0.0.0"),0));
        transport.setMaxInboundMessageSize(65507);
        snmp=new Snmp(dispatcher,transport);
        try {snmp.listen();}catch(Exception e){snmp.close();throw e;}
    }
    static DeviceProtocol.Failure failure(String code,String message){return new DeviceProtocol.Failure(code,message);}
    private static void requirePassword(String value){if(value==null || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<8)throw failure("SNMP_INVALID_CREDENTIAL","SNMP USM passphrases require at least eight octets.");}
    private static String blank(String s){return s==null?"":s;}
    private long remaining(){
        long ms=Duration.ofNanos(deadline-System.nanoTime()).toMillis();
        if(closed || Thread.currentThread().isInterrupted() || ms<=0)throw failure("SNMP_DEADLINE","SNMP collection exceeded its time budget.");
        if(auxiliary){ms=Math.min(ms,Duration.ofNanos(auxiliaryDeadline-System.nanoTime()).toMillis());if(ms<=0)throw failure("SNMP_ENDPOINT_BUDGET","Optional SNMP endpoint collection reached its time budget.");}
        return ms;
    }
    private PDU request(int type){PDU p=target.getVersion()==SnmpConstants.version3?new ScopedPDU():new PDU();p.setType(type);
        if(p instanceof ScopedPDU scoped)scoped.setContextName(new OctetString(blank(config.contextName())));return p;}
    private PDU send(PDU pdu) throws Exception {
        long wait=Math.min(remaining(),target.getTimeout()+1000);
        var future=new CompletableFuture<ResponseEvent<?>>();
        ResponseListener listener=new ResponseListener(){public <A extends Address> void onResponse(ResponseEvent<A> event){future.complete(event);}};
        try{
            snmp.send(pdu,target,null,listener);
            var event=future.get(wait,TimeUnit.MILLISECONDS);
            PDU response=event.getResponse();
            if(event.getError()!=null)throw failure("SNMP_TRANSPORT_ERROR","SNMP transport could not complete the request.");
            if(response==null)throw failure("SNMP_TIMEOUT","SNMP did not reply; verify reachability, credentials and access permissions.");
            if(response.getType()==PDU.REPORT)throw failure("SNMP_AUTHENTICATION_FAILED","SNMP v3 rejected the security context.");
            if(response.getErrorStatus()!=PDU.noError){
                int status=response.getErrorStatus(),index=response.getErrorIndex();
                String rejected=index>0&&index<=pdu.size()?pdu.get(index-1).getOid().toDottedString():"unspecified";
                throw failure(status==PDU.authorizationError||status==PDU.noAccess?"SNMP_ACCESS_DENIED":"SNMP_AGENT_ERROR",
                    "The SNMP agent rejected a read request (status="+status+", index="+index+", oid="+rejected+").");
            }
            if(response.size()>remainingVariables()){mark(budgetCode());throw failure(budgetCode(),"SNMP variable budget was reached.");}
            variables+=response.size();return response;
        }catch(TimeoutException e){throw failure("SNMP_TIMEOUT","SNMP did not reply within the request deadline.");}
        finally{snmp.cancel(pdu,listener);}
    }
    Map<String,Variable> get(List<String> oids, boolean required) throws Exception {
        Map<String,Variable> result=new LinkedHashMap<>();
        for(int offset=0;offset<oids.size();offset+=40){
            if(remainingVariables()==0){mark(budgetCode());break;}
            int end=Math.min(oids.size(),Math.min(offset+40,offset+remainingVariables()));
            var requested=new HashSet<OID>();var pdu=request(PDU.GET);
            for(String oid:oids.subList(offset,end)){OID key=new OID(oid);requested.add(key);pdu.add(new VariableBinding(key));}
            try{
                for(var vb:send(pdu).getVariableBindings()){
                    if(!requested.contains(vb.getOid())){mark("SNMP_UNEXPECTED_OID");continue;}
                    if(!vb.isException() && !(vb.getVariable() instanceof Null))result.put(vb.getOid().toDottedString(),vb.getVariable());
                }
            }catch(DeviceProtocol.Failure e){
                if(required)throw e;mark(e.code());
                // Some agents reject the entire GET when one optional object is unsupported.
                // Bisect only an explicit agent error; never multiply timeout/security retries.
                if("SNMP_AGENT_ERROR".equals(e.code()) && end-offset>1){
                    int middle=offset+(end-offset)/2;
                    result.putAll(get(oids.subList(offset,middle),false));
                    result.putAll(get(oids.subList(middle,end),false));
                }else if("SNMP_AGENT_ERROR".equals(e.code())){if(rejectedOptionalOids.size()<32)rejectedOptionalOids.add(oids.get(offset));}
                else break;
            }
        }
        return result;
    }
    Map<Integer,Variable> walk(String column,int limit,String limitFlag) throws Exception {
        var result=new LinkedHashMap<Integer,Variable>();
        walkIndexed(column,1,limit,limitFlag).forEach((suffix,value)->result.put(Integer.parseInt(suffix),value));return result;
    }
    /** Exact suffix arity prevents joining unrelated or malformed table rows. */
    Map<String,Variable> walkIndexed(String column,int arity,int limit,String limitFlag) throws Exception {
        if(arity<1 || arity>3 || limit<1 || limit>512)throw new IllegalArgumentException("Invalid bounded SNMP table walk");
        return walkSuffixes(column,arity,arity,limit,limitFlag,true);
    }
    /** Raw unsigned suffixes for TimeFilter and length-prefixed management-address indexes. */
    Map<String,Variable> walkSuffixes(String column,int minArity,int maxArity,int limit,String limitFlag) throws Exception {
        return walkSuffixes(column,minArity,maxArity,limit,limitFlag,false);
    }
    /** OCTET STRING table indexes retain their length prefix as part of the join key. */
    Map<String,Variable> walkOctetStringIndex(String column,int maxIndexOctets,int limit,String limitFlag)throws Exception{
        return walkOctetStringIndex(column,maxIndexOctets,limit,limitFlag,10);
    }
    Map<String,Variable> walkOctetStringIndex(String column,int maxIndexOctets,int limit,String limitFlag,int maxRepetitions)throws Exception{
        return walkOctetStringIndex(column,maxIndexOctets,limit,limitFlag,maxRepetitions,false);
    }
    /** Legacy BMC sensor columns can contain legal NULL/unavailable values; exceptions still terminate. */
    Map<String,Variable> walkNullableOctetStringIndex(String column,int maxIndexOctets,int limit,String limitFlag,int maxRepetitions)throws Exception{
        return walkOctetStringIndex(column,maxIndexOctets,limit,limitFlag,maxRepetitions,true);
    }
    private Map<String,Variable> walkOctetStringIndex(String column,int maxIndexOctets,int limit,String limitFlag,int maxRepetitions,boolean retainNull)throws Exception{
        if(maxIndexOctets<1||maxIndexOctets>64)throw new IllegalArgumentException("Invalid SNMP string index length");
        var result=new LinkedHashMap<String,Variable>();
        for(var row:walkSuffixes(column,2,maxIndexOctets+1,limit,limitFlag,false,maxRepetitions,retainNull).entrySet()){
            String[] index=row.getKey().split("\\.");
            if(Long.parseLong(index[0])!=index.length-1||Arrays.stream(index).skip(1).anyMatch(v->Long.parseLong(v)>255)){mark("SNMP_UNEXPECTED_INDEX");continue;}
            result.put(row.getKey(),row.getValue());
        }
        return result;
    }
    private Map<String,Variable> walkSuffixes(String column,int minArity,int maxArity,int limit,String limitFlag,boolean signedIndices) throws Exception {
        return walkSuffixes(column,minArity,maxArity,limit,limitFlag,signedIndices,10);
    }
    private Map<String,Variable> walkSuffixes(String column,int minArity,int maxArity,int limit,String limitFlag,boolean signedIndices,int maxRepetitions) throws Exception {
        return walkSuffixes(column,minArity,maxArity,limit,limitFlag,signedIndices,maxRepetitions,false);
    }
    private Map<String,Variable> walkSuffixes(String column,int minArity,int maxArity,int limit,String limitFlag,boolean signedIndices,int maxRepetitions,boolean retainNull) throws Exception {
        if(minArity<1 || maxArity<minArity || maxArity>65 || limit<1 || limit>512)throw new IllegalArgumentException("Invalid bounded SNMP table walk");
        if(maxRepetitions<1||maxRepetitions>(retainNull?20:10))throw new IllegalArgumentException("Invalid SNMP repetition limit");
        var result=new LinkedHashMap<String,Variable>();OID root=new OID(column),cursor=root;
        while(result.size()<limit){
            if(remainingVariables()==0){mark(budgetCode());break;}
            PDU pdu=request(PDU.GETBULK);pdu.setNonRepeaters(0);
            pdu.setMaxRepetitions(Math.min(maxRepetitions,Math.min(limit-result.size(),remainingVariables())));pdu.add(new VariableBinding(cursor));
            PDU response;
            try{response=send(pdu);}catch(DeviceProtocol.Failure e){mark(e.code());break;}
            if(response.size()==0)break;
            for(var vb:response.getVariableBindings()){
                OID oid=vb.getOid();
                if(vb.isException() || (!retainNull&&vb.getVariable() instanceof Null) || !oid.startsWith(root))return result;
                if(oid.compareTo(cursor)<=0){mark("SNMP_NON_INCREASING_OID");return result;}
                cursor=oid;
                if(oid.size()<root.size()+minArity || oid.size()>root.size()+maxArity){mark("SNMP_UNEXPECTED_INDEX");continue;}
                String suffix=oid.toDottedString().substring(root.toDottedString().length()+1);
                if(Arrays.stream(suffix.split("\\.")).anyMatch(part->{try{return Long.parseLong(part)>(signedIndices?Integer.MAX_VALUE:0xffffffffL);}catch(NumberFormatException e){return true;}})){mark("SNMP_UNEXPECTED_INDEX");continue;}
                result.put(suffix,vb.getVariable());
                if(result.size()==limit){if(limitFlag!=null)mark(limitFlag);return result;}
            }
        }
        return result;
    }
    void engineFacts(Map<String,String> facts){
        facts.put("snmpVariableCount",Integer.toString(variables));
        if(!rejectedOptionalOids.isEmpty())facts.put("snmpRejectedOptionalOids",String.join(",",rejectedOptionalOids));
        if(target instanceof UserTarget<?> user){
            byte[] engine=user.getAuthoritativeEngineID();
            if(engine==null || engine.length==0){var entry=((MPv3)snmp.getMessageProcessingModel(MPv3.ID)).getEngineID(target.getAddress());engine=entry==null?null:entry.getValue();}
            if(engine!=null && engine.length>0){
                facts.put("snmpEngineId",HexFormat.of().formatHex(engine));
                var time=usm.getTimeTable().getTime(new OctetString(engine));
                if(time!=null)facts.put("snmpEngineBoots",Integer.toString(time.getEngineBoots()));
            }
        }
    }
    @Override public void close(){closed=true;try{snmp.close();}catch(Exception ignored){}}
}
