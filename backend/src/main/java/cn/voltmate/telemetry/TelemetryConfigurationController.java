package cn.voltmate.telemetry;

import cn.voltmate.auth.AppSessions;
import cn.voltmate.core.ApiException;
import cn.voltmate.tesla.*;
import cn.voltmate.vehicle.VehicleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicles/{vehicleId}/telemetry/configuration")
public class TelemetryConfigurationController {
    private final AppSessions sessions;
    private final VehicleService vehicles;
    private final TeslaConfig config;
    private final TeslaHttp http;
    private final TeslaTokens tokens;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String hostname;
    private final String caFile;
    private final int port;

    public TelemetryConfigurationController(AppSessions sessions, VehicleService vehicles, TeslaConfig config,
        TeslaHttp http, TeslaTokens tokens, JdbcTemplate db, ObjectMapper json,
        @Value("${app.tesla.telemetry-host:}") String hostname,
        @Value("${app.tesla.telemetry-ca-file:}") String caFile,
        @Value("${app.tesla.telemetry-port:443}") int port) {
        this.sessions=sessions;this.vehicles=vehicles;this.config=config;this.http=http;this.tokens=tokens;
        this.db=db;this.json=json;this.hostname=hostname;this.caFile=caFile;this.port=port;
    }

    @GetMapping
    public JsonNode current(@RequestHeader("Authorization") String authorization,@PathVariable long vehicleId){
        var target=target(authorization,vehicleId);
        return http.get(config.proxyUrl+"/api/1/vehicles/"+target.vin()+"/fleet_telemetry_config",tokens.access(target.accountId()));
    }

    @PostMapping
    public JsonNode configure(@RequestHeader("Authorization") String authorization,@PathVariable long vehicleId){
        var target=target(authorization,vehicleId);
        if(!target.scopes().contains("vehicle_device_data"))throw new ApiException(HttpStatus.FORBIDDEN,"SCOPE_REQUIRED","Tesla 未授予车辆数据权限");
        if(!"PAIRED".equals(vehicles.one(target.userId(),vehicleId).keyState()))
            throw new ApiException(HttpStatus.CONFLICT,"KEY_NOT_PAIRED","请先完成虚拟钥匙配对");
        if(hostname.isBlank()||caFile.isBlank()||port<1||port>65535)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"TELEMETRY_NOT_CONFIGURED","请配置遥测域名、端口与证书链");
        if(hostname.contains(":")||hostname.contains("/")||hostname.contains(" "))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"TELEMETRY_HOST","遥测地址必须是独立域名");
        try {
            String ca=Files.readString(Path.of(caFile));
            if(!ca.contains("-----BEGIN CERTIFICATE-----"))throw new IllegalArgumentException("Invalid CA certificate");
            Map<String,Object> fields=new LinkedHashMap<>();
            for(String field:List.of("VehicleSpeed","Location","Odometer","BatteryLevel","ChargeState","ACChargingPower","DCChargingPower","ACChargingEnergyIn","DCChargingEnergyIn")) {
                if(field.equals("Location")&&(!target.locationEnabled()||!target.scopes().contains("vehicle_location")))continue;
                fields.put(field,Map.of("interval_seconds",field.equals("VehicleSpeed")?10:field.equals("Location")?30:60));
            }
            Map<String,Object> request=Map.of("vins",List.of(target.vin()),"config",Map.of(
                "hostname",hostname,"port",port,"ca",ca,"fields",fields,"alert_types",List.of("service")));
            return http.post(config.proxyUrl+"/api/1/vehicles/fleet_telemetry_config",tokens.access(target.accountId()),json.writeValueAsString(request));
        }catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"TELEMETRY_CERTIFICATE","遥测证书链不可读取或无效");}
    }

    @DeleteMapping
    public Map<String,String> disconnect(@RequestHeader("Authorization") String authorization,@PathVariable long vehicleId){
        var target=target(authorization,vehicleId);
        http.delete(config.proxyUrl+"/api/1/vehicles/"+target.vin()+"/fleet_telemetry_config",tokens.access(target.accountId()));
        return Map.of("status","disconnected");
    }

    private Target target(String authorization,long vehicleId){
        if(!"real".equals(config.mode))throw new ApiException(HttpStatus.BAD_REQUEST,"REAL_MODE_REQUIRED","演示车辆不需要遥测配置");
        long user=sessions.require(authorization);
        vehicles.owned(user,vehicleId);
        var rows=db.queryForList("SELECT v.vin,v.account_id,a.scopes,a.authorization_state,COALESCE(s.location_enabled,TRUE) AS location_enabled FROM vehicles v JOIN tesla_accounts a ON a.id=v.account_id LEFT JOIN user_settings s ON s.user_id=? WHERE v.id=? AND a.user_id=?",user,vehicleId,user);
        if(rows.isEmpty()||!"ACTIVE".equals(rows.getFirst().get("authorization_state")))
            throw new ApiException(HttpStatus.FORBIDDEN,"TESLA_AUTH_EXPIRED","Tesla 授权已失效");
        var row=rows.getFirst();
        return new Target(user,(String)row.get("vin"),((Number)row.get("account_id")).longValue(),
            List.of(((String)row.get("scopes")).split(" ")),Boolean.TRUE.equals(row.get("location_enabled")));
    }

    private record Target(long userId,String vin,long accountId,List<String> scopes,boolean locationEnabled){}
}
