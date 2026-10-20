package com.surprising.gateway.c2c;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

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
        } catch(Exception failure) {close();throw failure;}
    }
    private void cloneSerialTable(String table,String column) {
        jdbc.execute("CREATE TABLE "+table+" (LIKE public."+table+" INCLUDING ALL)");
        // LIKE 的 BIGSERIAL 默认会指向原序列，必须改用本用例 schema 自己的序列。
        jdbc.execute("CREATE SEQUENCE "+table+"_qa_sequence");
        jdbc.execute("ALTER TABLE "+table+" ALTER COLUMN "+column+" SET DEFAULT nextval('"+table+"_qa_sequence')");
    }
    <T> T transaction(Supplier<T> work) {return transactions.execute(status->work.get());}
    @Override public void close() {
        if(!schema.matches("c2c_it_[a-f0-9]{32}")) throw new IllegalStateException("fixture owner mismatch");
        jdbc.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
    }
}
