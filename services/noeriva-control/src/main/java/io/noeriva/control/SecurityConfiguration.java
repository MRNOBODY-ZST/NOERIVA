package io.noeriva.control;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;

@Configuration
public class SecurityConfiguration {
    public record Operator(String username,String password,String organizationId,List<String> roles) implements UserDetails {
        @Override public Collection<? extends GrantedAuthority> getAuthorities() {return roles.stream().map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList();}
        @Override public String getPassword(){return password;}
        @Override public String getUsername(){return username;}
    }
    @Bean PasswordEncoder passwordEncoder() {return new BCryptPasswordEncoder(10);}
    @Bean ReactiveUserDetailsService users(Environment env,PasswordEncoder encoder,ObjectProvider<DatabaseClient> database) {
        if(Arrays.asList(env.getActiveProfiles()).contains("demo") || Arrays.asList(env.getDefaultProfiles()).contains("demo") && env.getActiveProfiles().length==0) {
            String encoded=encoder.encode(env.getProperty("NOERIVA_BOOTSTRAP_PASSWORD","noeriva-local-demo"));
            return username -> switch(username) {
                case "admin" -> Mono.just(new Operator("admin",encoded,"demo",List.of("ADMIN","OPERATOR")));
                case "viewer" -> Mono.just(new Operator("viewer",encoded,"demo",List.of("VIEWER")));
                case "collector" -> Mono.just(new Operator("collector",encoder.encode(env.getProperty("NOERIVA_COLLECTOR_PASSWORD","noeriva-local-demo")),"demo",List.of("COLLECTOR")));
                default -> Mono.empty();
            };
        }
        return username -> database.getObject().sql("SELECT username,password_hash,organization_id,roles FROM app_user WHERE username=:name AND enabled=1")
            .bind("name",username).map((r,m)->(UserDetails)new Operator(r.get("username",String.class),r.get("password_hash",String.class),r.get("organization_id",String.class),List.of(r.get("roles",String.class).split(",")))).one();
    }
    @Bean SecurityWebFilterChain security(ServerHttpSecurity http,ReactiveUserDetailsService users,PasswordEncoder encoder,SessionTokens tokens) {
        var manager=new UserDetailsRepositoryReactiveAuthenticationManager(users);
        manager.setPasswordEncoder(encoder);
        manager.setScheduler(Schedulers.newBoundedElastic(4,64,"password-verification"));
        var bearer=new org.springframework.security.web.server.authentication.AuthenticationWebFilter((org.springframework.security.authentication.ReactiveAuthenticationManager) authentication -> tokens.username(authentication.getCredentials().toString())
            .flatMap(users::findByUsername).filter(UserDetails::isEnabled)
            .map(u->(org.springframework.security.core.Authentication)new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(u,null,u.getAuthorities()))
            .switchIfEmpty(Mono.error(new org.springframework.security.authentication.BadCredentialsException("Invalid or expired session"))));
        bearer.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        bearer.setServerAuthenticationConverter(exchange->{String value=exchange.getRequest().getHeaders().getFirst("Authorization");return value!=null&&value.startsWith("Bearer ")?Mono.just(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("session",value.substring(7))):Mono.empty();});
        return http.authenticationManager(manager)
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .requestCache(c->c.requestCache(NoOpServerRequestCache.getInstance()))
            // Stateless header-only API. An explicit non-simple header below prevents browser form CSRF;
            // no credentialed CORS policy or cookie authentication is enabled.
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(Customizer.withDefaults()).formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .addFilterBefore(bearer,SecurityWebFiltersOrder.HTTP_BASIC)
            .authorizeExchange(a->a
                .pathMatchers("/actuator/health","/actuator/health/**").permitAll()
                .pathMatchers("/actuator/prometheus").hasAnyRole("ADMIN","METRICS")
                .pathMatchers("/api/v1/devices/*/connections","/api/v1/devices/*/connections/**").hasRole("ADMIN")
                .pathMatchers(HttpMethod.POST,"/api/v1/settings").hasRole("ADMIN")
                .pathMatchers(HttpMethod.POST,"/api/v1/settings/password").hasAnyRole("ADMIN","OPERATOR","VIEWER")
                .pathMatchers(HttpMethod.POST,"/api/v1/applications/devices/**","/api/v1/nat-audit/devices/**").hasRole("ADMIN")
                .pathMatchers(HttpMethod.POST,"/api/v1/discovery/runs","/api/v1/discovery/candidates/*/register","/api/v1/discovery/candidates/*/link").hasAnyRole("ADMIN","OPERATOR")
                .pathMatchers(HttpMethod.GET,"/api/v1/discovery/candidates").hasAnyRole("ADMIN","OPERATOR","VIEWER")
                .pathMatchers("/api/v1/ingest/**").hasRole("COLLECTOR")
                .pathMatchers(HttpMethod.POST,"/api/v1/workbench/check-results","/api/v1/workbench/network-evidence").hasAnyRole("ADMIN","COLLECTOR")
                .pathMatchers(HttpMethod.POST,"/api/v1/workbench/investigations").hasAnyRole("ADMIN","OPERATOR","VIEWER")
                .pathMatchers(HttpMethod.POST,"/api/v1/**").hasAnyRole("ADMIN","OPERATOR")
                .pathMatchers(HttpMethod.GET,"/api/v1/**").hasAnyRole("ADMIN","OPERATOR","VIEWER")
                .anyExchange().denyAll())
            .addFilterBefore((exchange,chain)->{
                var method=exchange.getRequest().getMethod();
                if(method!=HttpMethod.GET && method!=HttpMethod.HEAD && method!=HttpMethod.OPTIONS && !"1".equals(exchange.getRequest().getHeaders().getFirst("X-Noeriva-Request"))) {
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);return exchange.getResponse().setComplete();
                }
                return chain.filter(exchange);
            },SecurityWebFiltersOrder.AUTHORIZATION)
            .build();
    }
}
