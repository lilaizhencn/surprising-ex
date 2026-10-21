package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.repository.C2cAdsRepository;
import com.surprising.gateway.c2c.repository.C2cFundsRepository;
import com.surprising.gateway.c2c.repository.C2cOrdersRepository;
import com.surprising.gateway.c2c.repository.C2cSubmissions;
import com.surprising.gateway.c2c.service.C2cAccess;
import com.surprising.gateway.c2c.service.C2cAdminAccess;
import com.surprising.gateway.c2c.service.C2cAdminMarkets;
import com.surprising.gateway.c2c.service.C2cAds;
import com.surprising.gateway.c2c.service.C2cAttachments;
import com.surprising.gateway.c2c.service.C2cConversations;
import com.surprising.gateway.c2c.service.C2cMerchantBonds;
import com.surprising.gateway.c2c.service.C2cMessages;
import com.surprising.gateway.c2c.service.C2cOrders;
import com.surprising.gateway.c2c.service.C2cPaymentAccounts;
import com.surprising.gateway.c2c.service.C2cProfiles;
import com.surprising.gateway.c2c.service.C2cR2Storage;

import com.surprising.gateway.c2c.repository.C2cAppealRepository;
import com.surprising.gateway.c2c.repository.C2cAttachmentRepository;
import com.surprising.gateway.c2c.repository.C2cConfigurationRepository;
import com.surprising.gateway.c2c.repository.C2cMarketRepository;
import com.surprising.gateway.c2c.repository.C2cMerchantBondRepository;
import com.surprising.gateway.c2c.repository.C2cMerchantRepository;
import com.surprising.gateway.c2c.repository.C2cOrderEventRepository;
import com.surprising.gateway.c2c.repository.C2cPaymentAccountRepository;
import com.surprising.gateway.c2c.repository.C2cPaymentMethodRepository;
import com.surprising.gateway.c2c.repository.C2cQuoteRepository;
import com.surprising.gateway.c2c.repository.C2cRelationRepository;
import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.c2c.service.C2cQueryService;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.GatewayUserRepository;
import com.surprising.gateway.provider.auth.TotpService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 每个用例独占并清理自己的 schema，不改共享用户、币种或资金表。 */
final class C2cDatabaseFixture implements AutoCloseable {
    final JdbcTemplate jdbc;
    final TransactionTemplate transactions;
    private final String schema="c2c_it_"+UUID.randomUUID().toString().replace("-","");
    C2cDatabaseFixture() throws Exception {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL");
        String user=System.getenv("INSTRUMENT_TEST_DB_USER"),password=System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        admin.execute("CREATE SCHEMA "+schema);
        var dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
        jdbc=new JdbcTemplate(dataSource);transactions=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try {
            jdbc.execute("""
                    CREATE TABLE gateway_users(user_id BIGSERIAL PRIMARY KEY,username TEXT,email TEXT,phone TEXT,
                        display_name TEXT NOT NULL DEFAULT '',
                        password_hash TEXT NOT NULL DEFAULT 'TEST_ONLY',status TEXT NOT NULL DEFAULT 'NORMAL',
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),updated_at TIMESTAMPTZ NOT NULL DEFAULT now())
                    """);
            jdbc.execute("CREATE TABLE assets (LIKE public.assets INCLUDING ALL)");
            jdbc.execute("CREATE TABLE gateway_user_kyc_profiles (LIKE public.gateway_user_kyc_profiles INCLUDING ALL)");
            jdbc.execute("CREATE TABLE gateway_roles(role_id BIGSERIAL PRIMARY KEY,role_code TEXT UNIQUE NOT NULL)");
            jdbc.update("INSERT INTO gateway_roles(role_code) VALUES('ADMIN'),('SUPPORT'),('SUPER_ADMIN')");
            jdbc.execute("CREATE TABLE gateway_permissions(permission_id BIGSERIAL PRIMARY KEY,permission_code TEXT UNIQUE NOT NULL,permission_name TEXT,description TEXT)");
            jdbc.execute("CREATE TABLE gateway_role_permissions(role_id BIGINT,permission_id BIGINT,PRIMARY KEY(role_id,permission_id))");
            cloneSerialTable("gateway_admin_approval_requests","approval_id");
            cloneSerialTable("gateway_admin_operation_logs","operation_id");
            jdbc.execute(Files.readString(Path.of("../deployment/migrations/20261020-c2c.sql")));
            jdbc.execute(Files.readString(Path.of("../deployment/migrations/20261020-c2c-chat.sql")));
            jdbc.execute(Files.readString(Path.of("../deployment/migrations/20261020-c2c-merchant-bond.sql")));
            jdbc.execute(Files.readString(Path.of("../deployment/migrations/20261021-c2c-eligible-ads.sql")));
        } catch(Exception failure) {close();throw failure;}
    }
    private void cloneSerialTable(String table,String column) {
        jdbc.execute("CREATE TABLE "+table+" (LIKE public."+table+" INCLUDING ALL)");
        // LIKE 的 BIGSERIAL 默认会指向原序列，必须改用本用例 schema 自己的序列。
        jdbc.execute("CREATE SEQUENCE "+table+"_qa_sequence");
        jdbc.execute("ALTER TABLE "+table+" ALTER COLUMN "+column+" SET DEFAULT nextval('"+table+"_qa_sequence')");
    }
    static C2cAds ads(JdbcTemplate jdbc, C2cAdsRepository rows, C2cMarketService markets, C2cPaymentAccounts payments,
                      C2cAccess access, C2cFundsRepository funds, C2cSubmissions submissions) {
        return new C2cAds(rows,markets,payments,access,funds,submissions,new GatewayUserRepository(jdbc),
                new C2cMerchantRepository(jdbc),new C2cPaymentMethodRepository(jdbc));
    }

    static C2cOrders orders(JdbcTemplate jdbc, C2cOrdersRepository rows, C2cAdsRepository ads, C2cMarketService markets,
                            C2cPaymentAccounts payments, C2cAccess access, C2cFundsRepository funds,
                            C2cSubmissions submissions, ObjectMapper json) {
        return new C2cOrders(rows,ads,markets,payments,access,funds,submissions,new GatewayUserRepository(jdbc),
                new C2cQuoteRepository(jdbc),new C2cOrderEventRepository(jdbc,json),new C2cAttachmentRepository(jdbc),
                new C2cAppealRepository(jdbc),new C2cMerchantRepository(jdbc),new C2cRelationRepository(jdbc));
    }

    static C2cQueryService queries(JdbcTemplate jdbc,C2cAdsRepository ads,C2cOrdersRepository orders,
                                    C2cMarketService markets,C2cPaymentAccounts payments,ObjectMapper json,C2cSubmissions submissions) {
        return new C2cQueryService(ads,orders,markets,new C2cMarketRepository(jdbc),payments,new C2cMerchantRepository(jdbc),
                new C2cPaymentMethodRepository(jdbc),new C2cPaymentAccountRepository(jdbc),new C2cOrderEventRepository(jdbc,json),
                new C2cAppealRepository(jdbc),submissions);
    }

    static C2cConversations conversations(JdbcTemplate jdbc,C2cOrdersRepository orders) {
        return new C2cConversations(new com.surprising.gateway.c2c.repository.C2cConversationRepository(jdbc),
                new com.surprising.gateway.c2c.repository.C2cMessageReadRepository(jdbc),orders,
                new com.surprising.gateway.c2c.repository.C2cProfileRepository(jdbc),new C2cRelationRepository(jdbc),new GatewayUserRepository(jdbc));
    }
    static C2cAttachments attachments(JdbcTemplate jdbc,C2cConversations conversations,C2cOrdersRepository orders,
                                      C2cPaymentAccounts payments,C2cR2Storage storage,org.springframework.transaction.PlatformTransactionManager transactions) {
        return new C2cAttachments(new C2cAttachmentRepository(jdbc),new com.surprising.gateway.c2c.repository.C2cMessageRepository(jdbc),
                new GatewayUserRepository(jdbc),conversations,orders,payments,storage,transactions);
    }
    static C2cMessages messages(JdbcTemplate jdbc,C2cConversations conversations,C2cAttachments attachments,ObjectMapper json) {
        return new C2cMessages(new com.surprising.gateway.c2c.repository.C2cMessageRepository(jdbc),
                new com.surprising.gateway.c2c.repository.C2cMessageReadRepository(jdbc),new com.surprising.gateway.c2c.repository.C2cConversationRepository(jdbc),
                new C2cOrderEventRepository(jdbc,json),new GatewayUserRepository(jdbc),conversations,attachments);
    }

    static C2cAdminMarkets adminMarkets(JdbcTemplate jdbc,C2cMarketService markets,C2cQueryService queries,C2cAdminAccess access,
                                       com.surprising.gateway.provider.product.GatewayProductServices products) {
        return new C2cAdminMarkets(markets,queries,access,products,new C2cConfigurationRepository(jdbc),new C2cMarketRepository(jdbc),
                new com.surprising.asset.repository.AssetRepository(jdbc),new C2cAdsRepository(jdbc),new C2cPaymentMethodRepository(jdbc),
                new C2cPaymentAccountRepository(jdbc),new C2cFundsRepository(jdbc));
    }

    static C2cMerchantBonds bonds(JdbcTemplate jdbc,C2cFundsRepository funds,C2cMarketService markets,C2cAccess access,
                                 C2cAdminAccess admin,com.surprising.gateway.provider.product.GatewayProductServices products) {
        return new C2cMerchantBonds(funds,markets,access,admin,products,new GatewayUserRepository(jdbc),
                new com.surprising.asset.repository.AssetRepository(jdbc),new C2cConfigurationRepository(jdbc),
                new com.surprising.gateway.c2c.repository.C2cMerchantBondPolicyRepository(jdbc),new C2cMerchantBondRepository(jdbc),
                new C2cMerchantRepository(jdbc),new C2cAdsRepository(jdbc),new C2cOrdersRepository(jdbc));
    }

    static C2cProfiles profiles(JdbcTemplate jdbc,C2cAccess access,ComplianceService compliance,GatewayUserRepository users,
                                C2cOrdersRepository orders,C2cMarketService markets,ObjectMapper json) {
        return new C2cProfiles(access,compliance,users,orders,markets,new com.surprising.gateway.c2c.repository.C2cProfileRepository(jdbc),
                new com.surprising.gateway.c2c.repository.C2cSecurityGrantRepository(jdbc),new C2cMerchantRepository(jdbc),
                new com.surprising.gateway.c2c.repository.C2cQuickReplyRepository(jdbc),new C2cRelationRepository(jdbc),
                new com.surprising.gateway.c2c.repository.C2cReportRepository(jdbc),new com.surprising.gateway.c2c.repository.C2cPriceAlertRepository(jdbc),
                new com.surprising.gateway.c2c.repository.C2cNotificationRepository(jdbc,json),new C2cFundsRepository(jdbc));
    }

    static C2cPaymentAccounts payments(JdbcTemplate jdbc, TotpService secrets,
                                       ObjectMapper json,
                                       ComplianceService compliance,C2cAccess access) {
        return new C2cPaymentAccounts(new C2cPaymentAccountRepository(jdbc),
                new C2cPaymentMethodRepository(jdbc),new C2cAdsRepository(jdbc),
                new C2cAttachmentRepository(jdbc),secrets,json,compliance,access);
    }

    static C2cMarketService markets(JdbcTemplate jdbc) {
        return new C2cMarketService(
                new C2cConfigurationRepository(jdbc),
                new C2cMarketRepository(jdbc),
                new C2cMerchantBondRepository(jdbc));
    }
    // Controlled Core fixture: a pre-existing approved merchant has already committed its bond.
    void seedMerchantBond(long userId) {
        jdbc.update("INSERT INTO c2c_merchant_bond_policy(singleton,asset,amount_units,updated_by) VALUES(true,'USDT',100000000,9) ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO c2c_merchant_bonds(bond_id,user_id,request_id,asset,amount_units,status) VALUES(?,?,?,'USDT',100000000,'LOCKED')",
                UUID.randomUUID(),userId,UUID.randomUUID());
    }
    <T> T transaction(Supplier<T> work) {return transactions.execute(status->work.get());}
    @Override public void close() {
        if(!schema.matches("c2c_it_[a-f0-9]{32}")) throw new IllegalStateException("fixture owner mismatch");
        jdbc.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
    }
}
