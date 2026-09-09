package io.noeriva.control.devices;

import java.net.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;

@Component
public class TargetPolicy {
    private record Network(byte[] bytes,int bits){
        boolean contains(byte[] address){
            if(address.length!=bytes.length)return false;
            for(int i=0;i<bits;i++)if((address[i/8]&(1<<(7-i%8)))!=(bytes[i/8]&(1<<(7-i%8))))return false;
            return true;
        }
    }
    private final List<Network> networks;
    private final Scheduler resolver=Schedulers.newBoundedElastic(4,32,"device-dns");
    public TargetPolicy(@Value("${NOERIVA_DEVICE_ALLOWED_CIDRS:10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7}") String cidrs){
        try{var result=new ArrayList<Network>();for(String cidr:cidrs.split(",")){
            String[] fields=cidr.trim().split("/",-1);if(fields.length!=2||!fields[0].matches("[0-9a-fA-F:.]+"))throw new IllegalArgumentException();
            byte[] bytes=InetAddress.getByName(fields[0]).getAddress();int bits=Integer.parseInt(fields[1]);if(bits<0||bits>bytes.length*8)throw new IllegalArgumentException();result.add(new Network(bytes,bits));
        }networks=List.copyOf(result);}catch(Exception e){throw new IllegalArgumentException("NOERIVA_DEVICE_ALLOWED_CIDRS must contain valid literal CIDR networks");}
    }
    static void validateHost(String host){
        if(host==null||host.isEmpty()||host.length()>253||!host.matches("[a-zA-Z0-9:.\\-]+")||host.startsWith("-")||host.endsWith("-")||host.contains(".."))
            throw new DeviceProtocol.Failure("INVALID_TARGET","Enter a host name or IP address without URL, path, credentials or zone identifier");
    }
    public Mono<String> resolve(String host){
        return Mono.fromCallable(()->{validateHost(host);return validateResolved(host,List.of(InetAddress.getAllByName(host)));})
            .subscribeOn(resolver).timeout(Duration.ofSeconds(3))
            .onErrorMap(e->e instanceof DeviceProtocol.Failure?e:new DeviceProtocol.Failure("TARGET_RESOLUTION_FAILED","The device host could not be resolved within its time budget"));
    }
    public String validateResolved(String host,List<InetAddress> addresses){
        validateHost(host);if(addresses.isEmpty()||addresses.size()>16)throw new DeviceProtocol.Failure("TARGET_NOT_ALLOWED","The target has no bounded allowed address set");
        for(InetAddress address:addresses){
            byte[] b=address.getAddress();
            // AWS and Google also expose metadata at ULA addresses, outside link-local ranges.
            if(isMetadataAddress(b)||address.isAnyLocalAddress()||address.isLinkLocalAddress()||address.isMulticastAddress()||b.length==4&&(b[0]&255)==255||networks.stream().noneMatch(n->n.contains(b)))
                throw new DeviceProtocol.Failure("TARGET_NOT_ALLOWED","The target is outside the configured device networks or is a reserved endpoint");
        }
        return addresses.getFirst().getHostAddress();
    }
    private static boolean isMetadataAddress(byte[] b){
        if(b.length!=16)return false;
        if((b[0]&255)==0xfd&&b[1]==0&&(b[2]&255)==0x0e&&(b[3]&255)==0xc2)return true;
        if((b[0]&255)!=0xfd||(b[1]&255)!=0x20||b[2]!=0||(b[3]&255)!=0xce||(b[15]&255)!=0x54||b[14]!=2)return false;
        for(int i=4;i<14;i++)if(b[i]!=0)return false;
        return true;
    }
    @jakarta.annotation.PreDestroy public void close(){resolver.dispose();}
}
