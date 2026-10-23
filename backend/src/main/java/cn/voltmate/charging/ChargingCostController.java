package cn.voltmate.charging;

import cn.voltmate.auth.AppSessions;
import cn.voltmate.core.*;
import cn.voltmate.vehicle.VehicleService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicles/{vehicleId}/charging")
public class ChargingCostController {
    public record ManualInput(BigDecimal amount){}
    private final AppSessions sessions;private final VehicleService vehicles;private final ChargingCostService costs;private final JdbcTemplate db;
    public ChargingCostController(AppSessions sessions,VehicleService vehicles,ChargingCostService costs,JdbcTemplate db){this.sessions=sessions;this.vehicles=vehicles;this.costs=costs;this.db=db;}
    @GetMapping("/sessions/{sessionId}/cost") public ChargingCostService.Cost cost(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId,@PathVariable long sessionId){
        long user=sessions.require(auth);check(user,vehicleId,sessionId);var cost=costs.calculate(user,sessionId);costs.save(user,cost);return cost;
    }
    @PutMapping("/sessions/{sessionId}/cost") public ChargingCostService.Cost manual(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId,@PathVariable long sessionId,@RequestBody ManualInput input){
        long user=sessions.require(auth);check(user,vehicleId,sessionId);return costs.manual(user,sessionId,input.amount());
    }
    @GetMapping("/cost-summary") public Map<String,Object> summary(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId,@RequestParam(defaultValue="30") int days,
        @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to){
        long user=sessions.require(auth);vehicles.owned(user,vehicleId);
        if(days<1||days>366)throw new ApiException(HttpStatus.BAD_REQUEST,"DATE_RANGE","天数应为 1–366");
        ZoneId zone=ZoneId.of("Asia/Shanghai");LocalDate end=to==null?LocalDate.now(zone):to,start=from==null?end.minusDays(days-1):from;
        if(start.isAfter(end)||java.time.temporal.ChronoUnit.DAYS.between(start,end)>366)throw new ApiException(HttpStatus.BAD_REQUEST,"DATE_RANGE","日期范围无效");
        var sessions=db.queryForList("SELECT id,started_at,grid_kwh FROM charging_sessions WHERE vehicle_id=? AND started_at>=? AND started_at<? ORDER BY started_at",
            vehicleId,java.sql.Timestamp.from(start.atStartOfDay(zone).toInstant()),java.sql.Timestamp.from(end.plusDays(1).atStartOfDay(zone).toInstant()));
        Map<String,BigDecimal> daily=new TreeMap<>(),byTariff=new TreeMap<>();BigDecimal total=BigDecimal.ZERO;int estimated=0,unavailable=0;
        for(var row:sessions){long id=((Number)row.get("id")).longValue();var cost=costs.calculate(user,id);
            if(cost.amount()==null){unavailable++;continue;}costs.save(user,cost);total=total.add(cost.amount());
            if(cost.accuracy().equals("ESTIMATED"))estimated++;
            cost.tariffBreakdown().forEach((name,amount)->byTariff.merge(name,amount,BigDecimal::add));
            if(cost.dailyBreakdown().isEmpty()){
                String day=SqlTime.instant(row.get("started_at")).atZone(zone).toLocalDate().toString();daily.merge(day,cost.amount(),BigDecimal::add);
            }else cost.dailyBreakdown().forEach((day,amount)->daily.merge(day,amount,BigDecimal::add));
        }
        return Map.of("currency","CNY","total",total,"daily",daily,"tariffBreakdown",byTariff,"estimatedCount",estimated,"unavailableCount",unavailable);
    }
    private void check(long user,long vehicleId,long sessionId){vehicles.owned(user,vehicleId);
        Integer count=db.queryForObject("SELECT COUNT(*) FROM charging_sessions WHERE id=? AND vehicle_id=?",Integer.class,sessionId,vehicleId);
        if(count==null||count==0)throw new ApiException(HttpStatus.NOT_FOUND,"CHARGE_NOT_FOUND","充电记录不存在");
    }
}
