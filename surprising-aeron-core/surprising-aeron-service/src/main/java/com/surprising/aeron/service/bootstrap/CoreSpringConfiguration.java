package com.surprising.aeron.service.bootstrap;

import com.surprising.aeron.service.cluster.ClusterTopology;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for node infrastructure; it deliberately excludes trading state classes. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoreNodeProperties.class)
public class CoreSpringConfiguration {

    @Bean
    public ClusterTopology clusterTopology(CoreNodeProperties properties) {
        return properties.topology();
    }

    @Bean(destroyMethod = "close")
    public SurprisingCoreNode surprisingCoreNode(ClusterTopology topology) {
        return new SurprisingCoreNode(topology);
    }
}
