package io.noeriva.control;

import org.springframework.context.annotation.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import reactor.core.publisher.Mono;
import java.time.Duration;

@Configuration @Profile("production")
public class ProductionConfiguration {
    @Bean TransactionalOperator transactions(ReactiveTransactionManager manager){return TransactionalOperator.create(manager);}
    @Bean DefaultErrorHandler kafkaErrorHandler(org.springframework.kafka.core.KafkaTemplate<String,String> kafka){
        // Bound retries, then require durable quarantine acknowledgement. A failed quarantine send
        // throws and retains the source record for retry; never silently discard a poison event.
        var quarantine=new org.springframework.kafka.listener.DeadLetterPublishingRecoverer(kafka,(record,error)->new org.apache.kafka.common.TopicPartition("noeriva.events.dlq.v1",record.partition()));
        quarantine.setFailIfSendResultIsError(true);
        quarantine.setWaitForSendResultTimeout(Duration.ofSeconds(15));
        var handler=new DefaultErrorHandler(quarantine,new FixedBackOff(2000,4));
        return handler;
    }
    @Bean CommandLineRunner bootstrap(DatabaseClient db,Environment env,PasswordEncoder passwords){return args->{
        String org=env.getProperty("NOERIVA_ORGANIZATION_ID","default");
        String password=env.getRequiredProperty("NOERIVA_BOOTSTRAP_PASSWORD");
        String collectorPassword=env.getRequiredProperty("NOERIVA_COLLECTOR_PASSWORD");
        String metricsPassword=env.getRequiredProperty("NOERIVA_METRICS_PASSWORD");
        if(password.length()<16||collectorPassword.length()<16)throw new IllegalArgumentException("Production bootstrap/collector secrets must have at least 16 characters");
        var bootstrap=db.sql("INSERT IGNORE INTO organization(id,name) VALUES(:org,'NOERIVA')").bind("org",org).fetch().rowsUpdated()
            .then(db.sql("INSERT IGNORE INTO site(organization_id,id,name,timezone) VALUES(:org,'default','Default site',NULL)").bind("org",org).fetch().rowsUpdated())
            .then(db.sql("INSERT IGNORE INTO app_user(username,organization_id,password_hash,roles) VALUES('admin',:org,:hash,'ADMIN,OPERATOR')").bind("org",org).bind("hash",passwords.encode(password)).fetch().rowsUpdated())
            .then(db.sql("INSERT IGNORE INTO app_user(username,organization_id,password_hash,roles) VALUES('collector',:org,:hash,'COLLECTOR')").bind("org",org).bind("hash",passwords.encode(collectorPassword)).fetch().rowsUpdated())
            .then(db.sql("INSERT IGNORE INTO app_user(username,organization_id,password_hash,roles) VALUES('metrics',:org,:hash,'METRICS')").bind("org",org).bind("hash",passwords.encode(metricsPassword)).fetch().rowsUpdated());
        // Startup-only migration/bootstrap boundary; never called from a Netty event loop.
        bootstrap.block(Duration.ofSeconds(30));
    };}
}
