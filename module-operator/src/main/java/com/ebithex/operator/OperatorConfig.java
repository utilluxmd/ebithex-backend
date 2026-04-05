package com.ebithex.operator;

import io.netty.channel.ChannelOption;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.retry.annotation.EnableRetry;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Configuration des adaptateurs opérateurs Mobile Money.
 *
 * Timeouts :
 *   - Connexion TCP  : 3 secondes (fast-fail si l'host est injoignable)
 *   - Réponse HTTP   : 30 secondes (délai max pour la réponse opérateur)
 *
 * Ces timeouts protègent le pool de threads contre les opérateurs lents.
 * @Retryable (spring-retry, 3 essais) s'applique en couche supérieure.
 */
@Configuration
@EnableRetry
public class OperatorConfig {

    @Bean
    public WebClientCustomizer operatorWebClientTimeoutCustomizer() {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
            .responseTimeout(Duration.ofSeconds(30));
        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);
        return builder -> builder.clientConnector(connector);
    }
}