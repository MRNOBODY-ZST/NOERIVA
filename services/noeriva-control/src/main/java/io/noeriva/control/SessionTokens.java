package io.noeriva.control;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Opaque, short-lived handles. Every use still loads current account permissions from the authority. */
@Component
public class SessionTokens {
    private final ObjectProvider<ReactiveStringRedisTemplate> redis;
    private final Map<String,Entry> demo=new ConcurrentHashMap<>();
    private final SecureRandom random=new SecureRandom();
    private record Entry(String username,Instant expires) {}
    public SessionTokens(ObjectProvider<ReactiveStringRedisTemplate> redis){this.redis=redis;}
    public Mono<String> issue(String username){return Mono.defer(()->{
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);String key=key(token);
        var store=redis.getIfAvailable();
        if(store!=null)return store.opsForValue().setIfAbsent(versionKey(username),UUID.randomUUID().toString()).then(store.opsForValue().get(versionKey(username))).switchIfEmpty(Mono.error(new IllegalStateException("Session generation unavailable"))).flatMap(version->store.opsForValue().set(key,username+"\n"+version,Duration.ofHours(1))).filter(Boolean::booleanValue).switchIfEmpty(Mono.error(new IllegalStateException("Session storage failed"))).thenReturn(token);
        demo.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now()));
        if(demo.size()>=4096)return Mono.error(new io.noeriva.query.QueryRejectedException("Session capacity reached"));
        demo.put(key,new Entry(username,Instant.now().plusSeconds(3600)));return Mono.just(token);
    });}
    public Mono<String> username(String token){return Mono.defer(()->{
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))return Mono.empty();
        var store=redis.getIfAvailable();if(store!=null)return store.opsForValue().get(key(token)).flatMap(value->{String[] parts=value.split("\n",-1);if(parts.length!=2||parts[1].isBlank())return Mono.empty();String username=parts[0];String issuedVersion=parts[1];return store.opsForValue().get(versionKey(username)).filter(issuedVersion::equals).map(v->username);});
        var entry=demo.get(key(token));return entry==null||entry.expires().isBefore(Instant.now())?Mono.empty():Mono.just(entry.username());
    });}
    public Mono<Void> revokeUser(String username){var store=redis.getIfAvailable();if(store!=null)return store.opsForValue().set(versionKey(username),UUID.randomUUID().toString()).then();return Mono.fromRunnable(()->demo.entrySet().removeIf(e->e.getValue().username().equals(username)));}
    private String versionKey(String username){return "noeriva:session-user-version:"+Base64.getUrlEncoder().withoutPadding().encodeToString(username.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private String key(String token){try{return "noeriva:session:v1:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
