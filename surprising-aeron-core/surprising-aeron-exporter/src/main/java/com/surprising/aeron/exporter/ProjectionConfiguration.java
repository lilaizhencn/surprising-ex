package com.surprising.aeron.exporter;

final class ProjectionConfiguration {

    private ProjectionConfiguration() {
    }

    static String databaseUrl() {
        return value("DATABASE_URL", "jdbc:postgresql://localhost:5432/postgres");
    }

    static String databaseUser() {
        return value("DATABASE_USER", "postgres");
    }

    static String databasePassword() {
        return value("DATABASE_PASSWORD", "postgres");
    }

    private static String value(String name, String fallback) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }
}
