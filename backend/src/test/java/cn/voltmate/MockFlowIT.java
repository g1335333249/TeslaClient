package cn.voltmate;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import cn.voltmate.telemetry.TelemetryIngestor;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockFlowIT {
    @Container static final MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4").withDatabaseName("voltmate");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",mysql::getJdbcUrl);
        r.add("spring.datasource.username",mysql::getUsername);
        r.add("spring.datasource.password",mysql::getPassword);
        r.add("app.mqtt.enabled",()->"false");
        r.add("app.mode",()->"mock");
    }
    @Autowired TestRestTemplate http;
    @Autowired TelemetryIngestor telemetry;
    @Autowired JdbcTemplate db;
    @Test void demoLoginListsVehiclesAndExecutesCommand(){
        Map<?,?> login=http.postForObject("/api/v1/auth/demo",Map.of("clientType","IOS"),Map.class);
        assertThat(login.get("token")).isNotNull();
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth((String)login.get("token"));
        var vehicles=http.exchange("/api/v1/vehicles",HttpMethod.GET,new HttpEntity<>(headers),Object[].class);
        assertThat(vehicles.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(vehicles.getBody()).hasSize(2);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var command=http.exchange("/api/v1/vehicles/2001/commands",HttpMethod.POST,
            new HttpEntity<>(Map.of("type","UNLOCK","idempotencyKey","test-command-000001"),headers),Map.class);
        assertThat(command.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(command.getBody().get("outcome")).isEqualTo("SUCCESS");
    }
    @Test void telemetryCreatesRawSampleAndTrip(){
        telemetry.ingest("telemetry/MOCKVIN0000000000/v/VehicleSpeed","12.5");
        telemetry.ingest("telemetry/MOCKVIN0000000000/v/Location","{\"latitude\":31.23,\"longitude\":121.47}");
        Integer raw=db.queryForObject("SELECT COUNT(*) FROM vehicle_telemetry_raw WHERE vehicle_id=2001",Integer.class);
        Integer trips=db.queryForObject("SELECT COUNT(*) FROM driving_trips WHERE vehicle_id=2001 AND ended_at IS NULL",Integer.class);
        assertThat(raw).isGreaterThanOrEqualTo(2);
        assertThat(trips).isEqualTo(1);
    }
}
