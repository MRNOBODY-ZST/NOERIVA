package io.noeriva.control;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** Serializes new collection claims and mutations with asset retirement. */
public final class DeviceLifecycle {
    private DeviceLifecycle() {}
    public static Mono<Void> activeLock(DatabaseClient db,String org,String device) {
        return db.sql("SELECT id FROM device WHERE organization_id=:org AND id=:device AND deleted_at IS NULL FOR UPDATE")
            .bind("org",org).bind("device",device).map((r,m)->r.get("id",String.class)).one()
            .switchIfEmpty(Mono.error(ApiException.missing())).then();
    }
    public static <T> Mono<T> activeTransaction(DatabaseClient db,String org,String device,Mono<T> operation) {
        return activeLock(db,org,device).then(operation)
            .as(TransactionalOperator.create(new R2dbcTransactionManager(db.getConnectionFactory()))::transactional);
    }
}
