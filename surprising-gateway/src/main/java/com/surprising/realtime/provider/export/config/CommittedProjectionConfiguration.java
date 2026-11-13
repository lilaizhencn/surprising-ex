package com.surprising.realtime.provider.export.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import com.surprising.realtime.provider.export.repository.CommittedOrderRepository;
import com.surprising.realtime.provider.export.service.CommittedOrderProjectionService;
import javax.sql.DataSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = {CommittedOrderRepository.class, CommittedOrderProjectionService.class})
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedProjectionConfiguration {
    /** 离线修复也使用相同的单表Repository、事务与代理配置，不另建写入路径。 */
    public static AnnotationConfigApplicationContext openPersistence(DataSource source) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "committed-projection-cli", java.util.Map.of("surprising.trade-export.enabled", "true")));
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(source));
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.register(CommittedProjectionConfiguration.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    @Bean
    public static PersistenceExceptionTranslationPostProcessor committedProjectionExceptionTranslation() {
        return new PersistenceExceptionTranslationPostProcessor();
    }

    @Bean
    public TransactionTemplate committedProjectionTransactions(PlatformTransactionManager transactions) {
        return new TransactionTemplate(transactions);
    }
}
