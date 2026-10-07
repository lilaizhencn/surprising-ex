package com.surprising.gateway.provider.auth;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CountryRepository {
    private final JdbcTemplate jdbc;
    public CountryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Country> enabled() {
        return jdbc.query("SELECT code, flag, names->>'en' AS en, names->>'zh' AS zh FROM gateway_countries WHERE enabled ORDER BY names->>'en'",
                (rs, row) -> new Country(rs.getString("code"), rs.getString("flag"), Map.of("en",rs.getString("en"),"zh",rs.getString("zh"))));
    }
    public boolean supported(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM gateway_countries WHERE code=? AND enabled)",Boolean.class,code));
    }
    public record Country(String code, String flag, Map<String,String> names) {}
}
