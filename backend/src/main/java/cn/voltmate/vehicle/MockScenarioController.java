package cn.voltmate.vehicle;

import cn.voltmate.auth.AppSessions;
import cn.voltmate.core.ApiException;
import cn.voltmate.tesla.TeslaConfig;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/mock/vehicles/{vehicleId}/scenario")
public class MockScenarioController {
    private final TeslaConfig config;private final AppSessions sessions;private final VehicleService vehicles;private final JdbcTemplate db;
    public MockScenarioController(TeslaConfig config,AppSessions sessions,VehicleService vehicles,JdbcTemplate db){this.config=config;this.sessions=sessions;this.vehicles=vehicles;this.db=db;}
    @PostMapping
    public Map<String,String> set(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId,@RequestBody Map<String,String> body){
        if(!config.mode.equals("mock"))throw new ApiException(HttpStatus.NOT_FOUND,"MOCK_DISABLED","演示模式未启用");
        vehicles.owned(sessions.require(auth),vehicleId);
        String scenario=body.getOrDefault("scenario","");
        switch(scenario){
            case "ONLINE" -> db.update("UPDATE vehicle_status_latest SET online_state='ONLINE' WHERE vehicle_id=?",vehicleId);
            case "ASLEEP" -> db.update("UPDATE vehicle_status_latest SET online_state='ASLEEP' WHERE vehicle_id=?",vehicleId);
            case "LOW_BATTERY" -> db.update("UPDATE vehicle_status_latest SET soc=12,range_km=65 WHERE vehicle_id=?",vehicleId);
            case "CHARGING" -> db.update("UPDATE vehicle_status_latest SET online_state='ONLINE',charging_state='CHARGING',charging_kw=7 WHERE vehicle_id=?",vehicleId);
            case "NO_LOCATION" -> db.update("UPDATE vehicle_status_latest SET latitude=NULL,longitude=NULL WHERE vehicle_id=?",vehicleId);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST,"SCENARIO","不支持该演示场景");
        }
        return Map.of("scenario",scenario,"status","applied");
    }
}
