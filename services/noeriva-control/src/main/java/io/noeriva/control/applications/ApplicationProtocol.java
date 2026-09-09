package io.noeriva.control.applications;
import io.noeriva.control.devices.DeviceProtocol;
import reactor.core.publisher.Mono;
import java.util.List;
public interface ApplicationProtocol {
    Mono<ApplicationModels.Sample> read(DeviceProtocol.Target target,DeviceProtocol.Secrets secrets,List<Integer> interfaces,int maxRows);
}
