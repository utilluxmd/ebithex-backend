package com.ebithex.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configuration des pools de threads asynchrones.
 *
 * Chaque domaine a son pool isolé pour éviter qu'une lenteur dans un
 * module (ex : webhook timeout) ne bloque les opérations financières.
 *
 * Pools configurés :
 *  - webhookExecutor       : dispatch des webhooks marchands
 *  - walletExecutor        : écritures au grand livre (ne jamais perdre)
 *  - operatorSyncExecutor  : synchronisation des statuts opérateurs
 *  - batchExecutor         : jobs de réconciliation et reporting
 *  - async (default)       : tous les @Async sans qualificateur
 */
@Configuration
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    /**
     * Pool webhooks — peut être lent (timeouts HTTP vers les marchands).
     * CallerRunsPolicy : jamais perdre un webhook si la queue est pleine.
     */
    @Bean("webhookExecutor")
    public Executor webhookExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(5);
        exec.setMaxPoolSize(20);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("webhook-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }

    /**
     * Pool wallet — écritures financières au grand livre.
     * CallerRunsPolicy : une écriture ne doit jamais être perdue.
     * Queue plus grande pour absorber les pics post-paiement.
     */
    @Bean("walletExecutor")
    public Executor walletExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(3);
        exec.setMaxPoolSize(10);
        exec.setQueueCapacity(1000);
        exec.setThreadNamePrefix("wallet-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }

    /**
     * Pool synchronisation opérateurs — polling statuts PROCESSING.
     * DiscardOldestPolicy : si en retard, abandonner les plus vieux
     * (un job de récupération tournera de toute façon).
     */
    @Bean("operatorSyncExecutor")
    public Executor operatorSyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(3);
        exec.setMaxPoolSize(10);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("op-sync-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        exec.initialize();
        return exec;
    }

    /**
     * Pool batch — réconciliation, reporting, expiration des transactions.
     * Peu de threads : les jobs sont lents mais peu prioritaires.
     * CallerRunsPolicy : si la queue est pleine, le thread appelant exécute la tâche
     * (throttle naturel) sans rejeter ni perdre le job de batch.
     */
    @Bean("batchExecutor")
    public Executor batchExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("batch-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }

    /**
     * Pool par défaut pour tous les @Async sans qualificateur.
     */
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(5);
        exec.setMaxPoolSize(15);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("async-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }

    /**
     * Active le support de l'annotation {@code @Timed} (Micrometer) via AOP.
     *
     * <p>Sans ce bean, {@code @Timed} est ignoré silencieusement — les métriques de latence
     * ne sont pas enregistrées. Requis pour {@code WebhookService.sendDelivery()},
     * {@code PaymentService.initiatePayment()}, etc.
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    /**
     * Handler global pour les exceptions levées dans les méthodes {@code @Async}.
     *
     * <p>Sans ce handler, toute exception dans un {@code @Async} est avalée silencieusement
     * par l'executor — aucun log, aucune alerte. Ce handler garantit qu'une trace exploitable
     * est émise pour chaque exception non gérée, permettant de détecter les défaillances
     * asynchrones (ex : webhook non livré, wallet non débité, email de notification perdu).
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
            log.error("[ASYNC ERROR] Exception non gérée dans {}.{}({}) : {}",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                Arrays.toString(params),
                ex.getMessage(),
                ex);
    }
}