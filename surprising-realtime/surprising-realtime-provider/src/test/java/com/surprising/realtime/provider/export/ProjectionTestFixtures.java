package com.surprising.realtime.provider.export;

import com.surprising.realtime.provider.export.repository.*;
import com.surprising.realtime.provider.export.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 只组装真实单表Repository和业务事务，测试不绕过生产Service或重复业务逻辑。 */
final class ProjectionTestFixtures {
    private ProjectionTestFixtures() { }
    static CommittedOrderProjectionService orders(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        return new CommittedOrderProjectionService(transaction,
                new CommittedOrderRepository(jdbc),
                new CommittedExecutionRepository(jdbc),
                new CommittedTriggerRepository(jdbc),
                new CommittedLiquidationRepository(jdbc),
                new LiquidationInitializationRepository(jdbc),
                new CommittedCommandResultRepository(jdbc),
                new ProjectionWatermarkRepository(jdbc),
                new ExecutionInitializationRepository(jdbc),
                new TriggerInitializationRepository(jdbc),
                new MarketMakerProjectionAccountsRepository(jdbc),
                new CommittedDeliveryLedgerRepository(jdbc),
                new CommittedFundingPageRepository(jdbc),
                new CommittedFundingPaymentRepository(jdbc),
                new CommittedFundingSettlementRepository(jdbc),
                statistics(jdbc, transaction), portfolio(jdbc, transaction), algos(jdbc, transaction));
    }
    static CommittedTradingStatisticsService statistics(JdbcTemplate jdbc, TransactionTemplate transaction) {
        return new CommittedTradingStatisticsService(new StatisticsInitializationRepository(jdbc),
                new StatisticsCursorRepository(jdbc), new CommandStatisticsRepository(jdbc),
                new StatisticsSnapshotRepository(jdbc), new CommittedExecutionRepository(jdbc), transaction);
    }
    static CommittedPortfolioHistoryService portfolio(JdbcTemplate jdbc, TransactionTemplate transaction) {
        return new CommittedPortfolioHistoryService(new PortfolioStartRepository(jdbc), new PortfolioCutoffRepository(jdbc),
                new PortfolioCashFlowRepository(jdbc), new PortfolioEquityRepository(jdbc), transaction);
    }
    static CommittedAlgoProjectionService algos(JdbcTemplate jdbc, TransactionTemplate transaction) {
        return new CommittedAlgoProjectionService(new CommittedAlgoRepository(jdbc), new AlgoInitializationRepository(jdbc), transaction);
    }
}
