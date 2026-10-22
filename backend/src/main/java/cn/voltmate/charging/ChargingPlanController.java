package cn.voltmate.charging;

import cn.voltmate.auth.AppSessions;
import cn.voltmate.core.*;
import cn.voltmate.tesla.*;
import cn.voltmate.vehicle.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicles/{vehicleId}/charging/plans")
public class ChargingPlanController {
    public record Input(BigDecimal latitude,BigDecimal longitude,Integer startMinute,Integer endMinute,boolean startEnabled,boolean endEnabled,String daysOfWeek,boolean enabled,boolean oneTime){}
    public record Plan(long id,long teslaScheduleId,Input settings,String applyState,String lastError,Instant updatedAt){}
    private final AppSessions sessions;private final VehicleService vehicles;private final VehicleCapabilityService capabilities;
    private final TeslaConfig config;private final TeslaHttp http;private final TeslaTokens tokens;private final JdbcTemplate db;private final Ids ids;private final VehicleEvents events;
    public ChargingPlanController(AppSessions sessions,VehicleService vehicles,VehicleCapabilityService capabilities,TeslaConfig config,TeslaHttp http,TeslaTokens tokens,JdbcTemplate db,Ids ids,VehicleEvents events){
        this.sessions=sessions;this.vehicles=vehicles;this.capabilities=capabilities;this.config=config;this.http=http;this.tokens=tokens;this.db=db;this.ids=ids;this.events=events;
    }
    @GetMapping public List<Plan> list(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId){
        long user=sessions.require(auth);vehicles.owned(user,vehicleId);
        return db.query("SELECT * FROM charging_plans WHERE vehicle_id=? AND user_id=? ORDER BY created_at DESC",(r,n)->new Plan(r.getLong("id"),r.getLong("tesla_schedule_id"),
            new Input(r.getBigDecimal("latitude"),r.getBigDecimal("longitude"),(Integer)r.getObject("start_minute"),(Integer)r.getObject("end_minute"),r.getBoolean("start_enabled"),r.getBoolean("end_enabled"),r.getString("days_of_week"),r.getBoolean("enabled"),r.getBoolean("one_time")),r.getString("apply_state"),r.getString("last_error"),r.getTimestamp("updated_at").toInstant()),vehicleId,user);
    }
    @PostMapping public Plan add(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId,@RequestBody Input input){
        long user=sessions.require(auth);vehicles.owned(user,vehicleId);validate(input);capabilities.requireCommand(user,vehicleId,"ADD_CHARGE_SCHEDULE");
        Integer locationDisabled=db.queryForObject("SELECT COUNT(*) FROM user_settings WHERE user_id=? AND location_enabled=FALSE",Integer.class,user);
        if(locationDisabled!=null&&locationDisabled>0)
            throw new ApiException(HttpStatus.FORBIDDEN,"LOCATION_DISABLED","请先允许保存位置以创建车辆充电计划");
        Integer recent=db.queryForObject("SELECT COUNT(*) FROM vehicle_command_logs WHERE user_id=? AND vehicle_id=? AND created_at>UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE",Integer.class,user,vehicleId);
        if(recent!=null&&recent>=10)throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"COMMAND_RATE_LIMIT","指令过于频繁");
        var duplicate=db.queryForList("SELECT id FROM charging_plans WHERE user_id=? AND vehicle_id=? AND latitude=? AND longitude=? AND start_minute<=>? AND end_minute<=>? AND days_of_week=? AND start_enabled=? AND end_enabled=? AND enabled=? AND one_time=? AND apply_state='APPLIED' AND created_at>UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE",
            user,vehicleId,input.latitude(),input.longitude(),input.startMinute(),input.endMinute(),input.daysOfWeek(),input.startEnabled(),input.endEnabled(),input.enabled(),input.oneTime());
        if(!duplicate.isEmpty()){long previous=((Number)duplicate.getFirst().get("id")).longValue();return list(auth,vehicleId).stream().filter(p->p.id()==previous).findFirst().orElseThrow();}
        long id=ids.next(),scheduleId=Math.max(1,id & 0x7fffffffL);
        String state="APPLIED",error=null;
        if(config.mode.equals("real")){
            var vehicle=db.queryForMap("SELECT vin,account_id FROM vehicles WHERE id=?",vehicleId);
            var account=((Number)vehicle.get("account_id")).longValue();
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("lat",input.latitude());body.put("lon",input.longitude());body.put("start_enabled",input.startEnabled());body.put("end_enabled",input.endEnabled());
            body.put("days_of_week",input.daysOfWeek());body.put("enabled",input.enabled());body.put("one_time",input.oneTime());body.put("id",scheduleId);
            if(input.startEnabled())body.put("start_time",input.startMinute());
            if(input.endEnabled())body.put("end_time",input.endMinute());
            try{
                String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
                var result=http.post(config.proxyUrl+"/api/1/vehicles/"+vehicle.get("vin")+"/command/add_charge_schedule",tokens.access(account),json);
                if(!result.path("response").path("result").asBoolean(false))throw new ApiException(HttpStatus.BAD_GATEWAY,"SCHEDULE_REJECTED","车辆未确认充电计划");
            }catch(ApiException e){state="FAILED";error=e.code;}catch(Exception e){state="FAILED";error="SCHEDULE_ERROR";}
        }
        db.update("INSERT INTO charging_plans(id,vehicle_id,user_id,tesla_schedule_id,latitude,longitude,start_minute,end_minute,start_enabled,end_enabled,days_of_week,enabled,one_time,apply_state,last_error,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
            id,vehicleId,user,scheduleId,input.latitude(),input.longitude(),input.startMinute(),input.endMinute(),input.startEnabled(),input.endEnabled(),input.daysOfWeek(),input.enabled(),input.oneTime(),state,error);
        audit(user,vehicleId,"ADD_CHARGE_SCHEDULE",state,error);
        events.publish(vehicleId,"COMMAND_RESULT",Map.of("type","ADD_CHARGE_SCHEDULE","outcome",state.equals("APPLIED")?"SUCCESS":"FAILED"));
        return list(auth,vehicleId).stream().filter(p->p.id()==id).findFirst().orElseThrow();
    }
    @DeleteMapping("/{id}") public Map<String,String> remove(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId,@PathVariable long id){
        long user=sessions.require(auth);vehicles.owned(user,vehicleId);
        var plans=list(auth,vehicleId).stream().filter(p->p.id()==id).toList();
        if(plans.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"PLAN_NOT_FOUND","充电计划不存在");
        capabilities.requireCommand(user,vehicleId,"REMOVE_CHARGE_SCHEDULE");
        if(config.mode.equals("real")&&plans.getFirst().applyState().equals("APPLIED")){
            var vehicle=db.queryForMap("SELECT vin,account_id FROM vehicles WHERE id=?",vehicleId);
            var result=http.post(config.proxyUrl+"/api/1/vehicles/"+vehicle.get("vin")+"/command/remove_charge_schedule",
                tokens.access(((Number)vehicle.get("account_id")).longValue()),"{\"id\":"+plans.getFirst().teslaScheduleId()+"}");
            if(!result.path("response").path("result").asBoolean(false))throw new ApiException(HttpStatus.BAD_GATEWAY,"SCHEDULE_REJECTED","车辆未确认删除计划");
        }
        db.update("DELETE FROM charging_plans WHERE id=? AND user_id=? AND vehicle_id=?",id,user,vehicleId);
        audit(user,vehicleId,"REMOVE_CHARGE_SCHEDULE","APPLIED",null);
        events.publish(vehicleId,"COMMAND_RESULT",Map.of("type","REMOVE_CHARGE_SCHEDULE","outcome","SUCCESS"));
        return Map.of("status","deleted");
    }
    private void audit(long user,long vehicle,String type,String state,String error){long id=ids.next();
        db.update("INSERT INTO vehicle_command_logs(id,vehicle_id,user_id,idempotency_key,command_type,outcome,error_code,created_at,completed_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
            id,vehicle,user,"schedule-"+id,type,state.equals("APPLIED")?"SUCCESS":"FAILED",error);
    }
    private static void validate(Input i){
        if(i==null||i.latitude()==null||i.longitude()==null||i.latitude().abs().compareTo(BigDecimal.valueOf(90))>0||i.longitude().abs().compareTo(BigDecimal.valueOf(180))>0||
            (!i.startEnabled()&&!i.endEnabled())||i.startEnabled()&&(i.startMinute()==null||i.startMinute()<0||i.startMinute()>1439)||
            i.endEnabled()&&(i.endMinute()==null||i.endMinute()<0||i.endMinute()>1439)||i.daysOfWeek()==null||!i.daysOfWeek().matches("(?i)(MON|TUES|WED|THURS|FRI|SAT|SUN|ALL|WEEKDAYS)(,(MON|TUES|WED|THURS|FRI|SAT|SUN)) *"))
            throw new ApiException(HttpStatus.BAD_REQUEST,"PLAN_INVALID","充电计划参数无效");
    }
}
