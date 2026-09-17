package io.noeriva.control;

import io.noeriva.query.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import java.time.*;

@Component @Profile("production")
public class MySqlRollupStateStore implements RollupStateStore {
    private static final String KEY="organization_id=:org AND device_id=:device AND interface_id=:interface AND source_id=:source AND direction=:direction";
    private final DatabaseClient db;private final TransactionalOperator tx;
    public MySqlRollupStateStore(DatabaseClient db,TransactionalOperator tx){this.db=db;this.tx=tx;}
    private DatabaseClient.GenericExecuteSpec bind(String sql,RollupKey k){return db.sql(sql).bind("org",k.organizationId()).bind("device",k.deviceId()).bind("interface",k.interfaceId()).bind("source",k.sourceId()).bind("direction",k.direction());}
    @Override public Mono<Long> nextRevision(RollupKey key){
        return bind("INSERT INTO rollup_progress(organization_id,device_id,interface_id,source_id,direction,next_revision) VALUES(:org,:device,:interface,:source,:direction,1) ON DUPLICATE KEY UPDATE next_revision=next_revision+1",key).fetch().rowsUpdated()

            .then(bind("SELECT next_revision FROM rollup_progress WHERE "+KEY,key).map((r,m)->r.get("next_revision",Long.class)).one()).as(tx::transactional);
    }
    @Override public Mono<Void> checkpoint(RollupKey key,Instant from,Instant through,Instant complete,long revision,double coverage){
        return bind("SELECT complete_from,complete_through FROM rollup_progress WHERE "+KEY+" FOR UPDATE",key)
            .map((r,m)->new Instant[]{at(r.get("complete_from",LocalDateTime.class)),at(r.get("complete_through",LocalDateTime.class))}).one()
            .flatMap(old->{
                Instant completeFrom=old[0],completeThrough=old[1];
                // A correction can invalidate an old completeness proof. Conservatively revoke the
                // entire proof interval; subsequent contiguous sweeps establish a fresh one.
                if((complete==null||complete.isBefore(through))&&completeFrom!=null&&from.isBefore(completeThrough)&&through.isAfter(completeFrom)){
                    completeFrom=null;completeThrough=null;
                }
                if(complete!=null){
                    if(completeThrough==null){completeFrom=from;completeThrough=complete;}
                    else if(!from.isAfter(completeThrough)&&!complete.isBefore(completeFrom)){
                        if(complete.isAfter(completeThrough))completeThrough=complete;
                        if(from.isBefore(completeFrom))completeFrom=from;
                    }
                }
                var q=bind("UPDATE rollup_progress SET processed_from=:from,processed_through=:through,complete_from=:completeFrom,complete_through=:completeThrough,coverage=:coverage,last_applied_revision=:revision WHERE "+KEY+" AND last_applied_revision<:revision",key)
                    .bind("from",local(from)).bind("through",local(through)).bind("coverage",coverage).bind("revision",revision);
                q=completeFrom==null?q.bindNull("completeFrom",LocalDateTime.class):q.bind("completeFrom",local(completeFrom));
                q=completeThrough==null?q.bindNull("completeThrough",LocalDateTime.class):q.bind("completeThrough",local(completeThrough));
                return q.fetch().rowsUpdated();
            }).as(tx::transactional).then();
    }
    private static Instant at(LocalDateTime at){return at==null?null:at.toInstant(ZoneOffset.UTC);}
    private static LocalDateTime local(Instant at){return LocalDateTime.ofInstant(at,ZoneOffset.UTC);}
}
