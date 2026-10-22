package cn.voltmate.settings;

import cn.voltmate.auth.AppSessions;
import cn.voltmate.core.*;
import cn.voltmate.vehicle.VehicleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/privacy")
public class PersonalDataController {
    private static final String CONFIRM="DELETE_MY_DATA";
    private final AppSessions sessions;private final VehicleService vehicles;private final JdbcTemplate db;private final ObjectMapper json;
    public PersonalDataController(AppSessions sessions,VehicleService vehicles,JdbcTemplate db,ObjectMapper json){this.sessions=sessions;this.vehicles=vehicles;this.db=db;this.json=json;}
    @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestHeader("Authorization") String auth,@RequestParam(defaultValue="json") String format){
        long user=sessions.require(auth);Map<String,List<Map<String,Object>>> data=new LinkedHashMap<>();
        data.put("settings",db.queryForList("SELECT retention_days,location_enabled,sample_interval_seconds FROM user_settings WHERE user_id=?",user));
        data.put("vehicles",db.queryForList("SELECT v.id,v.vin,v.display_name,v.model,v.region FROM vehicles v JOIN vehicle_user_access a ON a.vehicle_id=v.id WHERE a.user_id=?",user));
        data.put("trips",db.queryForList("SELECT t.* FROM driving_trips t JOIN vehicle_user_access a ON a.vehicle_id=t.vehicle_id WHERE a.user_id=?",user));
        data.put("trip_points",db.queryForList("SELECT p.* FROM driving_trip_points p JOIN driving_trips t ON t.id=p.trip_id JOIN vehicle_user_access a ON a.vehicle_id=t.vehicle_id WHERE a.user_id=?",user));
        data.put("charging_sessions",db.queryForList("SELECT c.* FROM charging_sessions c JOIN vehicle_user_access a ON a.vehicle_id=c.vehicle_id WHERE a.user_id=?",user));
        data.put("daily_statistics",db.queryForList("SELECT s.* FROM vehicle_daily_statistics s JOIN vehicle_user_access a ON a.vehicle_id=s.vehicle_id WHERE a.user_id=?",user));
        try{
            if(format.equalsIgnoreCase("json")){byte[] bytes=json.writeValueAsBytes(data);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=voltmate-data.json").cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(bytes);}
            if(!format.equalsIgnoreCase("csv"))throw new ApiException(HttpStatus.BAD_REQUEST,"FORMAT_INVALID","仅支持 JSON 或 CSV");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(ZipOutputStream zip=new ZipOutputStream(out,StandardCharsets.UTF_8)){
                for(var entry:data.entrySet()){
                    zip.putNextEntry(new ZipEntry(entry.getKey()+".csv"));
                    if(!entry.getValue().isEmpty()){
                        var keys=new ArrayList<>(entry.getValue().getFirst().keySet());
                        zip.write((String.join(",",keys)+"\n").getBytes(StandardCharsets.UTF_8));
                        for(var row:entry.getValue()){List<String> cells=new ArrayList<>();for(String key:keys)cells.add(csv(row.get(key)));zip.write((String.join(",",cells)+"\n").getBytes(StandardCharsets.UTF_8));}
                    }zip.closeEntry();
                }
            }
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=voltmate-data-csv.zip").cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/zip")).body(out.toByteArray());
        }catch(IOException e){throw new IllegalStateException("Export failed",e);}
    }
    @DeleteMapping("/vehicles/{vehicleId}/trips") @Transactional public Map<String,String> deleteTrips(@RequestHeader("Authorization") String auth,@RequestHeader("X-Confirm-Delete") String confirm,@PathVariable long vehicleId){
        long user=sessions.require(auth);verify(confirm);exclusive(user,vehicleId);
        db.update("DELETE p FROM driving_trip_points p JOIN driving_trips t ON t.id=p.trip_id WHERE t.vehicle_id=?",vehicleId);
        db.update("DELETE FROM driving_trips WHERE vehicle_id=?",vehicleId);
        db.update("UPDATE vehicle_daily_statistics SET distance_km=0,driving_kwh=0,trip_count=0 WHERE vehicle_id=?",vehicleId);
        db.update("DELETE FROM vehicle_signal_latest WHERE vehicle_id=? AND signal_name IN ('VehicleSpeed','Odometer','Location')",vehicleId);
        db.update("DELETE FROM vehicle_telemetry_samples WHERE vehicle_id=? AND signal_name IN ('VehicleSpeed','Odometer','Location')",vehicleId);
        db.update("DELETE FROM vehicle_telemetry_raw WHERE vehicle_id=? AND SUBSTRING_INDEX(topic,'/',-1) IN ('VehicleSpeed','Odometer','Location')",vehicleId);
        return Map.of("status","deleted");
    }
    @DeleteMapping("/vehicles/{vehicleId}/charging") @Transactional public Map<String,String> deleteCharging(@RequestHeader("Authorization") String auth,@RequestHeader("X-Confirm-Delete") String confirm,@PathVariable long vehicleId){
        long user=sessions.require(auth);verify(confirm);exclusive(user,vehicleId);clearCharging(vehicleId);
        db.update("UPDATE vehicle_daily_statistics SET charging_kwh=0,charging_cost=0,charge_count=0 WHERE vehicle_id=?",vehicleId);
        return Map.of("status","deleted");
    }
    @DeleteMapping("/account") @Transactional public Map<String,String> deleteAccount(@RequestHeader("Authorization") String auth,@RequestHeader("X-Confirm-Delete") String confirm){
        long user=sessions.require(auth);verify(confirm);
        var ids=db.queryForList("SELECT DISTINCT v.id AS vehicle_id,a.user_id AS owner_user_id FROM vehicles v LEFT JOIN tesla_accounts a ON a.id=v.account_id WHERE a.user_id=? OR EXISTS(SELECT 1 FROM vehicle_user_access x WHERE x.vehicle_id=v.id AND x.user_id=?)",user,user);
        for(var v:ids){long id=((Number)v.get("vehicle_id")).longValue();Object owner=v.get("owner_user_id");
            if(owner!=null&&((Number)owner).longValue()!=user)continue;
            Integer others=db.queryForObject("SELECT COUNT(*) FROM vehicle_user_access WHERE vehicle_id=? AND user_id<>?",Integer.class,id,user);
            if(others!=null&&others>0)throw new ApiException(HttpStatus.CONFLICT,"SHARED_VEHICLE","共享车辆需先解除其他用户访问");}
        db.update("DELETE l FROM automation_execution_logs l JOIN automation_rules r ON r.id=l.rule_id WHERE r.user_id=?",user);
        db.update("DELETE FROM automation_rules WHERE user_id=?",user);
        for(var v:ids){long id=((Number)v.get("vehicle_id")).longValue();Object owner=v.get("owner_user_id");
            if(owner!=null&&((Number)owner).longValue()!=user)db.update("DELETE FROM vehicle_user_access WHERE vehicle_id=? AND user_id=?",id,user);
            else clearVehicle(id);
        }
        db.update("DELETE FROM charging_plans WHERE user_id=?",user);
        db.update("DELETE FROM charging_session_costs WHERE user_id=?",user);
        db.update("DELETE FROM vehicle_command_logs WHERE user_id=?",user);
        db.update("DELETE FROM notification_records WHERE user_id=?",user);
        db.update("DELETE FROM oauth_login_tickets WHERE user_id=?",user);
        db.update("DELETE FROM oauth_states WHERE user_id=?",user);
        db.update("DELETE FROM charging_tariffs WHERE user_id=?",user);
        db.update("DELETE FROM user_settings WHERE user_id=?",user);
        db.update("DELETE FROM user_sessions WHERE user_id=?",user);
        db.update("DELETE FROM tesla_tokens WHERE account_id IN (SELECT id FROM tesla_accounts WHERE user_id=?)",user);
        db.update("DELETE FROM tesla_accounts WHERE user_id=?",user);
        db.update("DELETE FROM users WHERE id=?",user);
        return Map.of("status","deleted","scope","VoltMate server data");
    }
    private void clearVehicle(long id){
        String vin=db.queryForObject("SELECT vin FROM vehicles WHERE id=?",String.class,id);
        db.update("DELETE FROM telemetry_ingestion_offsets WHERE topic LIKE ?","telemetry/"+vin+"/%");
        db.update("DELETE FROM charging_plans WHERE vehicle_id=?",id);clearCharging(id);
        db.update("DELETE p FROM driving_trip_points p JOIN driving_trips t ON t.id=p.trip_id WHERE t.vehicle_id=?",id);
        db.update("DELETE FROM driving_trips WHERE vehicle_id=?",id);
        db.update("DELETE FROM vehicle_daily_statistics WHERE vehicle_id=?",id);
        db.update("DELETE FROM vehicle_command_logs WHERE vehicle_id=?",id);
        db.update("DELETE FROM vehicle_capability_snapshots WHERE vehicle_id=?",id);
        db.update("DELETE FROM vehicle_signal_latest WHERE vehicle_id=?",id);
        db.update("DELETE FROM vehicle_telemetry_samples WHERE vehicle_id=?",id);
        db.update("DELETE FROM vehicle_telemetry_raw WHERE vehicle_id=?",id);
        db.update("DELETE FROM vehicle_status_latest WHERE vehicle_id=?",id);
        db.update("DELETE FROM vehicle_user_access WHERE vehicle_id=?",id);
        db.update("DELETE FROM vehicles WHERE id=?",id);
    }
    private void clearCharging(long id){
        db.update("DELETE c FROM charging_session_costs c JOIN charging_sessions s ON s.id=c.session_id WHERE s.vehicle_id=?",id);
        db.update("DELETE e FROM charging_energy_samples e JOIN charging_sessions s ON s.id=e.session_id WHERE s.vehicle_id=?",id);
        db.update("DELETE FROM charging_sessions WHERE vehicle_id=?",id);
        db.update("DELETE FROM vehicle_signal_latest WHERE vehicle_id=? AND signal_name IN ('ACChargingPower','DCChargingPower','ACChargingEnergyIn','DCChargingEnergyIn','ChargeState','DetailedChargeState','ChargeLimitSoc')",id);
        db.update("DELETE FROM vehicle_telemetry_samples WHERE vehicle_id=? AND signal_name IN ('ACChargingPower','DCChargingPower','ACChargingEnergyIn','DCChargingEnergyIn','ChargeState','DetailedChargeState','ChargeLimitSoc')",id);
        db.update("DELETE FROM vehicle_telemetry_raw WHERE vehicle_id=? AND SUBSTRING_INDEX(topic,'/',-1) IN ('ACChargingPower','DCChargingPower','ACChargingEnergyIn','DCChargingEnergyIn','ChargeState','DetailedChargeState','ChargeLimitSoc')",id);
    }
    private void exclusive(long user,long id){vehicles.owned(user,id);Integer count=db.queryForObject("SELECT COUNT(*) FROM vehicle_user_access WHERE vehicle_id=?",Integer.class,id);
        if(count!=null&&count>1)throw new ApiException(HttpStatus.CONFLICT,"SHARED_VEHICLE","共享车辆需先解除其他用户访问，避免删除他人数据");
    }
    private static void verify(String value){if(!CONFIRM.equals(value))throw new ApiException(HttpStatus.BAD_REQUEST,"DELETE_CONFIRMATION","请二次确认删除");}
    private static String csv(Object raw){if(raw==null)return "";String value=raw.toString();if(value.startsWith("=")||value.startsWith("+")||value.startsWith("-")||value.startsWith("@"))value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";}
}
