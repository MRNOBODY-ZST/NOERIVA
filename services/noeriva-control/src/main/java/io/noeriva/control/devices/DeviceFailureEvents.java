package io.noeriva.control.devices;

import io.noeriva.control.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Collection diagnostics never advance the successful source checkpoint or device lastSeen. */
@Component
public final class DeviceFailureEvents {
    private final HistoryStore history;private final DatabaseClient db;
    public DeviceFailureEvents(ObjectProvider<HistoryStore> history,ObjectProvider<DatabaseClient> db){this.history=history.getIfAvailable();this.db=db.getIfAvailable();}
    public Mono<Void> failed(DeviceAccessModels.Stored before,DeviceAccessModels.Stored after,String code){
        if(db==null)return Mono.empty();
        boolean repeated="ERROR".equals(before.status())&&Objects.equals(before.errorCode(),code);
        Instant at=Objects.requireNonNullElse(after.lastAttemptAt(),Instant.now());
        String message=after.protocol()+" 采集失败（"+code+"）："+reason(code)+"；最近成功采集 "+Objects.toString(after.lastSuccessAt(),"从未成功");
        String alert=id(after),title=message.substring(0,Math.min(message.length(),240));
        var write=db.sql((repeated?"INSERT IGNORE":"INSERT")+" INTO alert(organization_id,id,device_id,device_name,severity,state,title,opened_at,revision) SELECT :org,:id,id,name,'WARNING','OPEN',:title,:at,1 FROM device WHERE organization_id=:org AND id=:device"+(repeated?"":" ON DUPLICATE KEY UPDATE state='OPEN',title=VALUES(title),severity='WARNING',opened_at=VALUES(opened_at),acknowledged_at=NULL,acknowledged_by=NULL,revision=alert.revision+1"))
            .bind("org",after.org()).bind("id",alert).bind("title",title).bind("at",LocalDateTime.ofInstant(at,ZoneOffset.UTC)).bind("device",after.device()).fetch().rowsUpdated();
        return write.flatMap(changed->changed>0?event(after,"DeviceCollectionFailed","WARNING",message,at):Mono.empty());
    }
    public Mono<Void> recovered(DeviceAccessModels.Stored before,DeviceAccessModels.Stored after){
        if(db==null||before.errorCode()==null||before.errorCode().isEmpty())return Mono.empty();
        return db.sql("UPDATE alert SET state='RESOLVED',revision=revision+1 WHERE organization_id=:org AND id=:id AND state<>'RESOLVED'").bind("org",after.org()).bind("id",id(after)).fetch().rowsUpdated()
            .then(event(after,"DeviceCollectionRecovered","INFO",after.protocol()+" 已恢复成功采集；上次失败 "+before.errorCode(),Instant.now()));
    }
    private Mono<Void> event(DeviceAccessModels.Stored value,String kind,String severity,String message,Instant at){
        if(history==null)return Mono.empty();
        String eventId=UUID.nameUUIDFromBytes((value.scope()+"/"+kind+"/"+value.sequence()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return history.append(value.org(),new Models.Observation(eventId,value.device(),DeviceAccessStore.source(value),kind,value.sourceEpoch(),value.sequence(),at,severity,Map.of(),message),Instant.now());
    }
    private static String id(DeviceAccessModels.Stored value){return "collection-"+UUID.nameUUIDFromBytes(value.scope().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static String reason(String code){
        if(code.contains("TIMEOUT")||code.contains("DEADLINE")||code.equals("COLLECTION_FAILED"))return "设备未在采集时限内返回有效响应，请核对电源、网络与访问策略";
        if(code.contains("AUTHENTICATION")||code.contains("AUTHORIZATION"))return "设备拒绝身份认证或读取权限";
        if(code.contains("HOST_KEY")||code.contains("CERTIFICATE"))return "设备身份校验未通过";
        if(code.equals("PUBLICATION_FAILED"))return "设备已响应，但数据库或指标发布失败";
        if(code.contains("CONNECT")||code.contains("TRANSPORT")||code.contains("CHANNEL"))return "无法完成到设备的协议连接";
        return "未得到可发布的设备观测，请检查采集来源诊断";
    }
}
