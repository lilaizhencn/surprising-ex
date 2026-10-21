package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cPaymentMethod;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_payment_methods。 */
@Repository
public class C2cPaymentMethodRepository {
    private final JdbcTemplate jdbc;

    public C2cPaymentMethodRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<C2cPaymentMethod> list(boolean enabledOnly) {
        return jdbc.query("SELECT * FROM c2c_payment_methods WHERE NOT ? OR enabled ORDER BY code", this::read, enabledOnly);
    }

    public Set<String> enabledCodes(String fiat) {
        return new HashSet<>(jdbc.query("SELECT code FROM c2c_payment_methods WHERE enabled AND ?=ANY(currencies)",
                (r, n) -> r.getString(1), fiat));
    }

    public Optional<C2cPaymentMethod> available(String code, String fiat) {
        return jdbc.query("SELECT * FROM c2c_payment_methods WHERE code=? AND enabled AND ?=ANY(currencies)",
                this::read, code, fiat).stream().findFirst();
    }

    private C2cPaymentMethod read(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new C2cPaymentMethod(r.getString("code"), r.getString("label"), r.getBoolean("enabled"),
                Arrays.asList((String[]) r.getArray("currencies").getArray()), r.getBoolean("requires_bank_name"),
                r.getBoolean("supports_qr"), r.getLong("revision"));
    }
}
