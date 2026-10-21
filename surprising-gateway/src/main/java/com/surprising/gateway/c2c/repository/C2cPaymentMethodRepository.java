package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cPaymentMethod;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_payment_methods。 */
@Repository
public class C2cPaymentMethodRepository {
    private final JdbcTemplate jdbc;

    public C2cPaymentMethodRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<C2cPaymentMethod> available(String code, String fiat) {
        return jdbc.query("SELECT * FROM c2c_payment_methods WHERE code=? AND enabled AND ?=ANY(currencies)",
                (r, n) -> new C2cPaymentMethod(r.getString("code"), r.getString("label"), r.getBoolean("enabled"),
                        Arrays.asList((String[]) r.getArray("currencies").getArray()), r.getBoolean("requires_bank_name"),
                        r.getBoolean("supports_qr"), r.getLong("revision")), code, fiat).stream().findFirst();
    }
}
