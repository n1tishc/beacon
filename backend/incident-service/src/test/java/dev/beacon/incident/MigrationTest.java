package dev.beacon.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Schema changes against a database that already holds data, which is what every real deploy meets.
 * Other integration tests only ever migrate an empty database.
 */
@Testcontainers
class MigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void freshSchema() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS incident CASCADE");
    }

    @Test
    void v2BackfillsATriggeredEntryForIncidentsCreatedBeforeIt() {
        migrateTo("1");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incident.incidents (id, title, severity, status, created_at, updated_at)
                VALUES (?, 'Created in slice 1', 'LOW', 'TRIGGERED', now(), now())
                """, id);

        migrateTo("2");

        assertThat(jdbc.queryForObject("SELECT version FROM incident.incidents WHERE id = ?", Long.class, id))
                .isZero();
        List<Map<String, Object>> entries = jdbc.queryForList(
                "SELECT type, actor, note FROM incident.timeline_entries WHERE incident_id = ?", id);
        assertThat(entries).containsExactly(Map.of("type", "TRIGGERED", "actor", "unknown", "note", "Severity LOW"));
    }

    @Test
    void v2RefusesAResolvedIncidentWithoutAResolvedAt() {
        migrateTo("2");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO incident.incidents (id, title, severity, status, created_at, updated_at)
                VALUES (?, 'Inconsistent', 'LOW', 'RESOLVED', now(), now())
                """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_incidents_resolved_at");
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas("incident")
                .defaultSchema("incident")
                .target(version)
                .load()
                .migrate();
    }
}
