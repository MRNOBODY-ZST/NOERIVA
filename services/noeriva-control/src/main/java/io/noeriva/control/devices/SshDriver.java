package io.noeriva.control.devices;

import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;

@Component
public final class SshDriver implements DeviceProtocol,AutoCloseable {
    private final Scheduler work=Schedulers.newBoundedElastic(8,32,"device-ssh",60,true);
    @Override public String protocol(){return "SSH";}
    @Override public Mono<Reading> read(Target target,Secrets secrets){
        return Mono.create(sink->{
            long deadline=System.nanoTime()+Duration.ofSeconds(25).toNanos();
            var cancelled=new AtomicBoolean();var active=new AtomicReference<SshSession>();var scheduled=new AtomicReference<Disposable>();
            sink.onCancel(()->{cancelled.set(true);var s=active.get();if(s!=null)s.close();var d=scheduled.get();if(d!=null)d.dispose();});
            try{scheduled.set(work.schedule(()->{
                if(cancelled.get())return;
                try(var connection=new SshSession(target,deadline)){
                    active.set(connection);if(cancelled.get())return;
                    SshProfiles.commands(target.sshProfile());connection.connect(secrets);
                    Reading result=SshProfiles.read(connection,target.sshProfile(),secrets.password());if(!cancelled.get())sink.success(result);
                }catch(Exception e){if(!cancelled.get())sink.error(e instanceof Failure?e:failure("SSH_CONNECTION_FAILED"));}
                finally{active.set(null);}
            }));if(cancelled.get())scheduled.get().dispose();}
            catch(Exception e){if(!cancelled.get())sink.error(failure("SSH_CAPACITY_EXCEEDED"));}
        });
    }
    static Failure failure(String code){return new Failure(code,switch(code){
        case "SSH_HOST_KEY_MISMATCH"->"SSH 主机密钥与已保存的 SHA-256 指纹不一致，请核对设备身份";
        case "SSH_AUTHENTICATION_FAILED"->"SSH 密码认证未成功，请核对账号、密码及接口权限";
        case "SSH_INVALID_HOST_KEY"->"请输入规范的 OpenSSH SHA256 主机密钥指纹";
        case "SSH_TIMEOUT"->"SSH 读取超出单次或总时间预算";
        case "SSH_OUTPUT_LIMIT"->"SSH 单条命令输出超过 256 KiB 限制";
        case "SSH_PROFILE_MISMATCH"->"SSH 返回的设备身份与所选只读档案不匹配";
        case "SSH_IDENTITY_UNAVAILABLE"->"SSH 未返回可识别的设备身份";
        case "SSH_CHANNEL_CLOSED"->"SSH 交互通道在完整响应到达前关闭";
        case "SSH_CANCELLED"->"SSH 读取已取消";
        case "SSH_CAPACITY_EXCEEDED"->"SSH 采集并发容量已满，请稍后重试";
        default->"SSH 读取未能在已配置的协议与安全限制内完成";
    });}
    @jakarta.annotation.PreDestroy @Override public void close(){work.dispose();}
}
