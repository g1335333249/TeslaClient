package cn.voltmate;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs only against disposable MySQL 8.4 databases; never uses application datasource settings. */
class MigrationCompatibilityIT {
    @Test void freshInstallAndLegacyUpgradePreserveData() throws Exception {
        try (var mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("migration_upgrade")) {
            mysql.start();
            String user = mysql.getUsername(), password = mysql.getPassword();
            String upgradeUrl = mysql.getJdbcUrl();
            Flyway legacy = flyway(upgradeUrl, user, password, "5");
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(5);

            try (var connection = DriverManager.getConnection(upgradeUrl, user, password);
                 Statement sql = connection.createStatement()) {
                sql.executeUpdate("INSERT INTO users(id,display_name,created_at) VALUES(81001,'历史用户',UTC_TIMESTAMP(6))");
                sql.executeUpdate("INSERT INTO vehicles(id,vin,display_name,region,key_state,created_at) VALUES(81002,'MIGRATIONVIN81002','历史车辆','CN','UNKNOWN',UTC_TIMESTAMP(6))");
                sql.executeUpdate("INSERT INTO vehicle_user_access(vehicle_id,user_id,access_level,created_at) VALUES(81002,81001,'OWNER',UTC_TIMESTAMP(6))");
                sql.executeUpdate("INSERT INTO vehicle_status_latest(vehicle_id,source,updated_at,soc) VALUES(81002,'MOCK',UTC_TIMESTAMP(6),42)");
                sql.executeUpdate("INSERT INTO vehicle_telemetry_raw(id,vehicle_id,event_key,topic,event_type,received_at,payload) VALUES(81003,81002,REPEAT('a',64),'telemetry/MIGRATIONVIN81002/v/BatteryLevel','v',UTC_TIMESTAMP(6),'{\"value\":42}')");
                sql.executeUpdate("INSERT INTO driving_trips(id,vehicle_id,started_at,completeness) VALUES(81004,81002,UTC_TIMESTAMP(6),'NO_LOCATION')");
                sql.executeUpdate("INSERT INTO charging_sessions(id,vehicle_id,started_at,currency) VALUES(81005,81002,UTC_TIMESTAMP(6),'CNY')");
                sql.executeUpdate("INSERT INTO charging_tariffs(id,user_id,name,price_per_kwh,starts_local,ends_local,timezone,created_at) VALUES(81006,81001,'历史电价',0.5000,'00:00:00','23:59:59','Asia/Shanghai',UTC_TIMESTAMP(6))");
            }

            Flyway upgraded = flyway(upgradeUrl, user, password, "9");
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(4);
            upgraded.validate();
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(upgradeUrl, user, password);
                 Statement sql = connection.createStatement()) {
                assertThat(count(sql, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")).isEqualTo(9);
                assertThat(count(sql, "SELECT COUNT(*) FROM users WHERE id=81001 AND collection_paused=0")).isEqualTo(1);
                assertThat(count(sql, "SELECT COUNT(*) FROM vehicles WHERE id=81002")).isEqualTo(1);
                assertThat(count(sql, "SELECT COUNT(*) FROM vehicle_telemetry_raw WHERE id=81003 AND processing_state='PROCESSED' AND ingested_at IS NOT NULL")).isEqualTo(1);
                assertThat(count(sql, "SELECT COUNT(*) FROM charging_tariffs WHERE id=81006 AND day_mask=127")).isEqualTo(1);
                assertThat(count(sql, "SELECT COUNT(*) FROM driving_trips WHERE id=81004")).isEqualTo(1);
                assertThat(count(sql, "SELECT COUNT(*) FROM charging_sessions WHERE id=81005")).isEqualTo(1);
                sql.executeUpdate("INSERT INTO charging_plans(id,vehicle_id,user_id,tesla_schedule_id,latitude,longitude,start_enabled,end_enabled,days_of_week,enabled,one_time,apply_state,created_at,updated_at) VALUES(81007,81002,81001,81007,31.2,121.4,1,0,'ALL',1,0,'APPLIED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
                assertThat(count(sql, "SELECT COUNT(*) FROM charging_plans WHERE id=81007")).isEqualTo(1);
            }

            try (var freshMysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("migration_fresh")) {
                freshMysql.start();
                String freshUrl = freshMysql.getJdbcUrl();
                Flyway fresh = flyway(freshUrl, freshMysql.getUsername(), freshMysql.getPassword(), "9");
                assertThat(fresh.migrate().migrationsExecuted).isEqualTo(9);
                fresh.validate();
                assertThat(fresh.migrate().migrationsExecuted).isZero();
                try (var connection = DriverManager.getConnection(freshUrl, freshMysql.getUsername(), freshMysql.getPassword());
                     Statement sql = connection.createStatement()) {
                    assertThat(count(sql, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")).isEqualTo(9);
                }
            }
        }
    }

    private static Flyway flyway(String url, String user, String password, String target) {
        return Flyway.configure().dataSource(url, user, password)
            .locations("classpath:db/migration").target(target).load();
    }

    private static int count(Statement sql, String query) throws Exception {
        try (ResultSet result = sql.executeQuery(query)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }
}
