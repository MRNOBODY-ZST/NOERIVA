package io.noeriva.query;
import java.time.Duration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
final class QueryHttpClient {
    private QueryHttpClient() {}
    static WebClient create(String url,String username,String password) {
        var builder=WebClient.builder().baseUrl(url)
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(Duration.ofSeconds(6))))
            .codecs(c->c.defaultCodecs().maxInMemorySize(2*1024*1024));
        if(username!=null&&!username.isBlank()) builder.defaultHeaders(h->h.setBasicAuth(username,password));
        return builder.build();
    }
}
