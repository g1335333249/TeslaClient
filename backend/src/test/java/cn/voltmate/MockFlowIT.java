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
import cn.voltmate.telemetry.TelemetryAggregator;
import cn.voltmate.auth.AppSessions;
import cn.voltmate.tesla.OAuthStateStore;
import cn.voltmate.tesla.TeslaTokens;
import cn.voltmate.tesla.TeslaHttp;
import cn.voltmate.core.Crypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

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
    @Autowired TelemetryAggregator aggregator;
    @Autowired JdbcTemplate db;
    @Autowired AppSessions sessions;
    @Autowired OAuthStateStore oauthStates;
    @Autowired Crypto crypto;
    @Autowired TeslaTokens teslaTokens;
    @MockitoBean TeslaHttp teslaHttp;
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
    @Test void duplicateAndOutOfOrderTelemetryKeepNewestStatus(){
        String now=java.time.Instant.now().minusSeconds(20).toString();
        String old=java.time.Instant.now().minusSeconds(60).toString();
        telemetry.ingest("telemetry/MOCKVIN0000000000/v/BatteryLevel","{\"eventId\":\"battery-new-001\",\"observedAt\":\""+now+"\",\"value\":82}");
        telemetry.ingest("telemetry/MOCKVIN0000000000/v/BatteryLevel","{\"eventId\":\"battery-new-001\",\"observedAt\":\""+now+"\",\"value\":82}");
        telemetry.ingest("telemetry/MOCKVIN0000000000/v/BatteryLevel","{\"eventId\":\"battery-old-001\",\"observedAt\":\""+old+"\",\"value\":20}");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vehicle_telemetry_raw WHERE payload LIKE '%battery-new-001%'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT soc FROM vehicle_status_latest WHERE vehicle_id=2001",java.math.BigDecimal.class)).isGreaterThan(new java.math.BigDecimal("70"));
    }
    @Test void invalidTimestampIsQuarantined(){
        telemetry.ingest("telemetry/MOCKVIN0000000000/v/BatteryLevel","{\"eventId\":\"bad-clock-001\",\"observedAt\":\"nonsense\",\"value\":10}");
        assertThat(db.queryForObject("SELECT processing_state FROM vehicle_telemetry_raw WHERE payload LIKE '%bad-clock-001%'",String.class)).isEqualTo("INVALID_TIMESTAMP");
    }
    @Test void privacyExportChecksSessionAndSupportsCsv(){
        Map<?,?> login=http.postForObject("/api/v1/auth/demo",Map.of("clientType","IOS"),Map.class);
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth((String)login.get("token"));
        var json=http.exchange("/api/v1/privacy/export?format=json",HttpMethod.GET,new HttpEntity<>(headers),String.class);
        assertThat(json.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.getBody()).contains("trips","vehicles");
        var csv=http.exchange("/api/v1/privacy/export?format=csv",HttpMethod.GET,new HttpEntity<>(headers),byte[].class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getBody()).startsWith((byte)'P',(byte)'K');
        var denied=http.getForEntity("/api/v1/privacy/export",String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void deletionRequiresVehicleOwnershipAndConfirmation(){
        db.update("INSERT IGNORE INTO users(id,display_name,created_at) VALUES(1002,'隔离测试',UTC_TIMESTAMP(6))");
        var token=(String)sessions.create(1002,"WEB").get("token");
        int tripsBefore=db.queryForObject("SELECT COUNT(*) FROM driving_trips WHERE vehicle_id=2001",Integer.class);
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(token);
        var denied=http.exchange("/api/v1/privacy/vehicles/2001/trips",HttpMethod.DELETE,new HttpEntity<>(headers),Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        headers.set("X-Confirm-Delete","DELETE_MY_DATA");
        denied=http.exchange("/api/v1/privacy/vehicles/2001/trips",HttpMethod.DELETE,new HttpEntity<>(headers),Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM driving_trips WHERE vehicle_id=2001",Integer.class)).isEqualTo(tripsBefore);
    }
    @Test void accountDeletionRemovesSessionsAndUser(){
        db.update("INSERT IGNORE INTO users(id,display_name,created_at) VALUES(1003,'待删除用户',UTC_TIMESTAMP(6))");
        var token=(String)sessions.create(1003,"WEB").get("token");
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(token);headers.set("X-Confirm-Delete","DELETE_MY_DATA");
        var result=http.exchange("/api/v1/privacy/account",HttpMethod.DELETE,new HttpEntity<>(headers),Map.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM users WHERE id=1003",Integer.class)).isZero();
        var again=http.exchange("/api/v1/privacy/export",HttpMethod.GET,new HttpEntity<>(headers),String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void personalDataDeletionKeepsAccountAndSession(){
        db.update("INSERT IGNORE INTO users(id,display_name,created_at) VALUES(1005,'保留账户',UTC_TIMESTAMP(6))");
        db.update("INSERT INTO vehicles(id,vin,display_name,region,key_state,created_at) VALUES(3005,'MOCKVINPRIVACY3005','隐私测试车','CN','PAIRED',UTC_TIMESTAMP(6))");
        db.update("INSERT INTO vehicle_user_access(vehicle_id,user_id,access_level,created_at) VALUES(3005,1005,'OWNER',UTC_TIMESTAMP(6))");
        db.update("INSERT INTO vehicle_status_latest(vehicle_id,source,updated_at,online_state) VALUES(3005,'MOCK',UTC_TIMESTAMP(6),'ONLINE')");
        db.update("INSERT INTO user_settings(user_id,retention_days,location_enabled,sample_interval_seconds,updated_at) VALUES(1005,90,TRUE,60,UTC_TIMESTAMP(6))");
        var token=(String)sessions.create(1005,"WEB").get("token");
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(token);headers.set("X-Confirm-Delete","DELETE_MY_DATA");
        var result=http.exchange("/api/v1/privacy/data",HttpMethod.DELETE,new HttpEntity<>(headers),Map.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_settings WHERE user_id=1005",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM users WHERE id=1005",Integer.class)).isEqualTo(1);
        assertThat(sessions.require("Bearer "+token)).isEqualTo(1005L);
        assertThat(db.queryForObject("SELECT collection_paused FROM users WHERE id=1005",Boolean.class)).isTrue();
        telemetry.ingest("telemetry/MOCKVINPRIVACY3005/v/BatteryLevel","50");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vehicle_telemetry_raw WHERE vehicle_id=3005",Integer.class)).isZero();
    }
    @Test void mockChargingPlanPersistsAndDeletesWithoutTeslaCall(){
        Map<?,?> login=http.postForObject("/api/v1/auth/demo",Map.of("clientType","IOS"),Map.class);
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth((String)login.get("token"));headers.setContentType(MediaType.APPLICATION_JSON);
        var body=Map.of("latitude",31.23,"longitude",121.47,"startMinute",1380,"endMinute",420,
            "startEnabled",true,"endEnabled",true,"daysOfWeek","ALL","enabled",true,"oneTime",false);
        var added=http.exchange("/api/v1/vehicles/2001/charging/plans",HttpMethod.POST,new HttpEntity<>(body,headers),Map.class);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(added.getBody().get("applyState")).isEqualTo("APPLIED");
        var planId=((Number)added.getBody().get("id")).longValue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM charging_plans WHERE id=?",Integer.class,planId)).isEqualTo(1);
        var removed=http.exchange("/api/v1/vehicles/2001/charging/plans/"+planId,HttpMethod.DELETE,new HttpEntity<>(headers),Map.class);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM charging_plans WHERE id=?",Integer.class,planId)).isZero();
    }
    @Test void failedMockCommandDoesNotPretendSuccess(){
        Map<?,?> login=http.postForObject("/api/v1/auth/demo",Map.of("clientType","IOS"),Map.class);
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth((String)login.get("token"));headers.setContentType(MediaType.APPLICATION_JSON);
        http.exchange("/api/v1/mock/vehicles/2001/scenario",HttpMethod.POST,new HttpEntity<>(Map.of("scenario","COMMAND_TIMEOUT"),headers),Map.class);
        try{
            var command=http.exchange("/api/v1/vehicles/2001/commands",HttpMethod.POST,new HttpEntity<>(Map.of("type","LOCK","idempotencyKey","timeout-test-000001"),headers),Map.class);
            assertThat(command.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(command.getBody().get("outcome")).isEqualTo("FAILED");
            assertThat(db.queryForObject("SELECT outcome FROM vehicle_command_logs WHERE idempotency_key='timeout-test-000001'",String.class)).isEqualTo("FAILED");
        } finally {http.exchange("/api/v1/mock/vehicles/2001/scenario",HttpMethod.POST,new HttpEntity<>(Map.of("scenario","CLEAR_FAULT"),headers),Map.class);}
    }
    @Test void lateLocationImprovesClosedTripOnlyWithTwoRealPoints(){
        var now=java.time.Instant.now();var start=now.minusSeconds(300);var end=now.minusSeconds(120);
        db.update("INSERT INTO driving_trips(id,vehicle_id,started_at,ended_at,completeness) VALUES(900001,2001,?,?, 'NO_LOCATION')",java.sql.Timestamp.from(start),java.sql.Timestamp.from(end));
        String topic="telemetry/MOCKVIN0000000000/v/Location";
        telemetry.ingest(topic,"{\"eventId\":\"late-gps-one\",\"observedAt\":\""+start.plusSeconds(60)+"\",\"value\":{\"latitude\":31.1,\"longitude\":121.1}}");
        assertThat(db.queryForObject("SELECT completeness FROM driving_trips WHERE id=900001",String.class)).isEqualTo("NO_LOCATION");
        telemetry.ingest(topic,"{\"eventId\":\"late-gps-two\",\"observedAt\":\""+start.plusSeconds(120)+"\",\"value\":{\"latitude\":31.2,\"longitude\":121.2}}");
        assertThat(db.queryForObject("SELECT completeness FROM driving_trips WHERE id=900001",String.class)).isEqualTo("COMPLETE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM driving_trip_points WHERE trip_id=900001",Integer.class)).isEqualTo(2);
    }
    @Test void tripAcrossUtcMidnightSplitsDistance(){
        var boundary=java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().minusSeconds(86400);
        var start=boundary.minusSeconds(600);var end=boundary.plusSeconds(600);
        var first=java.sql.Date.valueOf(start.atZone(java.time.ZoneOffset.UTC).toLocalDate());
        var second=java.sql.Date.valueOf(end.atZone(java.time.ZoneOffset.UTC).toLocalDate());
        java.math.BigDecimal beforeFirst=dailyDistance(first),beforeSecond=dailyDistance(second);
        db.update("INSERT INTO driving_trips(id,vehicle_id,started_at,last_motion_at,distance_km,completeness) VALUES(900002,2001,?,?,20,'IN_PROGRESS')",java.sql.Timestamp.from(start),java.sql.Timestamp.from(end));
        aggregator.closeIdle();
        assertThat(dailyDistance(first).subtract(beforeFirst)).isEqualByComparingTo("10");
        assertThat(dailyDistance(second).subtract(beforeSecond)).isEqualByComparingTo("10");
    }
    private java.math.BigDecimal dailyDistance(java.sql.Date day){return db.query("SELECT distance_km FROM vehicle_daily_statistics WHERE vehicle_id=2001 AND day_utc=?",(r,n)->r.getBigDecimal(1),day).stream().findFirst().orElse(java.math.BigDecimal.ZERO);}
    @Test void oauthStateCannotBeReplayed(){
        String state="state-"+java.util.UUID.randomUUID();
        db.update("INSERT INTO oauth_states(state_hash,nonce_hash,client_type,expires_at,created_at) VALUES(?,?, 'IOS',UTC_TIMESTAMP(6)+INTERVAL 10 MINUTE,UTC_TIMESTAMP(6))",crypto.hash(state),crypto.hash("nonce"));
        assertThat(oauthStates.consume(state).get("client_type")).isEqualTo("IOS");
        assertThatThrownBy(()->oauthStates.consume(state)).isInstanceOf(cn.voltmate.core.ApiException.class);
    }
    @Test void concurrentRefreshRotatesOnlyOnce() throws Exception {
        db.update("INSERT IGNORE INTO users(id,display_name,created_at) VALUES(1004,'令牌测试',UTC_TIMESTAMP(6))");
        db.update("INSERT INTO tesla_accounts(id,user_id,region,tesla_subject,scopes,authorization_state,created_at) VALUES(900004,1004,'CN','token-test','vehicle_device_data','ACTIVE',UTC_TIMESTAMP(6))");
        db.update("INSERT INTO tesla_tokens(account_id,access_ciphertext,refresh_ciphertext,expires_at,updated_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6))",
            900004,crypto.encrypt("old-access"),crypto.encrypt("old-refresh"),java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)));
        when(teslaHttp.form(anyString(),anyMap())).thenReturn(new ObjectMapper().readTree("{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600}"));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->teslaTokens.access(900004));var second=pool.submit(()->teslaTokens.access(900004));
            assertThat(first.get()).isEqualTo("new-access");assertThat(second.get()).isEqualTo("new-access");
            verify(teslaHttp,times(1)).form(anyString(),anyMap());
            assertThat(crypto.decrypt(db.queryForObject("SELECT refresh_ciphertext FROM tesla_tokens WHERE account_id=900004",String.class))).isEqualTo("new-refresh");
        }finally{pool.shutdownNow();}
    }
}
