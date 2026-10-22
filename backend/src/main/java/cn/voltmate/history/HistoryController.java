package cn.voltmate.history;

import cn.voltmate.auth.AppSessions;
import cn.voltmate.core.*;
import cn.voltmate.vehicle.VehicleService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicles/{vehicleId}")
public class HistoryController {
    public record Trip(long id,Instant startedAt,Instant endedAt,BigDecimal distanceKm,BigDecimal energyKwh,String completeness){}
    public record Point(Instant observedAt,BigDecimal latitude,BigDecimal longitude){}
    public record TripDetail(Trip trip,List<Point> points){}
    public record Charge(long id,Instant startedAt,Instant endedAt,BigDecimal gridKwh,BigDecimal batteryKwh,BigDecimal estimatedKwh,BigDecimal estimatedCost,String currency){}
    public record Day(LocalDate day,BigDecimal distanceKm,BigDecimal drivingKwh,BigDecimal chargingKwh,BigDecimal chargingCost,int tripCount,int chargeCount){}
    private final AppSessions sessions;private final VehicleService vehicles;private final JdbcTemplate db;
    public HistoryController(AppSessions sessions,VehicleService vehicles,JdbcTemplate db){this.sessions=sessions;this.vehicles=vehicles;this.db=db;}
    @GetMapping("/trips")
    public List<Trip> trips(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        vehicles.owned(sessions.require(auth),vehicleId);checkPage(page,size);
        return db.query("SELECT * FROM driving_trips WHERE vehicle_id=? ORDER BY started_at DESC LIMIT ? OFFSET ?",
            (r,n)->new Trip(r.getLong("id"),r.getTimestamp("started_at").toInstant(),instant(r.getTimestamp("ended_at")),r.getBigDecimal("distance_km"),r.getBigDecimal("energy_kwh"),r.getString("completeness")),vehicleId,size,page*size);
    }
    @GetMapping("/trips/{tripId}")
    public TripDetail trip(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId,@PathVariable long tripId){
        vehicles.owned(sessions.require(auth),vehicleId);
        var trips=db.query("SELECT * FROM driving_trips WHERE id=? AND vehicle_id=?",(r,n)->new Trip(r.getLong("id"),r.getTimestamp("started_at").toInstant(),instant(r.getTimestamp("ended_at")),r.getBigDecimal("distance_km"),r.getBigDecimal("energy_kwh"),r.getString("completeness")),tripId,vehicleId);
        if(trips.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"TRIP_NOT_FOUND","行程不存在");
        var points=db.query("SELECT observed_at,latitude,longitude FROM driving_trip_points WHERE trip_id=? ORDER BY observed_at",(r,n)->new Point(r.getTimestamp(1).toInstant(),r.getBigDecimal(2),r.getBigDecimal(3)),tripId);
        return new TripDetail(trips.getFirst(),points);
    }
    @GetMapping("/charging/sessions")
    public List<Charge> charging(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        vehicles.owned(sessions.require(auth),vehicleId);checkPage(page,size);
        return db.query("SELECT * FROM charging_sessions WHERE vehicle_id=? ORDER BY started_at DESC LIMIT ? OFFSET ?",
            (r,n)->new Charge(r.getLong("id"),r.getTimestamp("started_at").toInstant(),instant(r.getTimestamp("ended_at")),r.getBigDecimal("grid_kwh"),r.getBigDecimal("battery_kwh"),r.getBigDecimal("estimated_kwh"),r.getBigDecimal("estimated_cost"),r.getString("currency")),vehicleId,size,page*size);
    }
    @GetMapping("/statistics/daily")
    public List<Day> daily(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId,@RequestParam(defaultValue="30") int days,
        @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to){
        vehicles.owned(sessions.require(auth),vehicleId);
        if(days<1||days>366)throw new ApiException(HttpStatus.BAD_REQUEST,"DATE_RANGE","天数应为 1–366");
        LocalDate end=to==null?LocalDate.now(ZoneOffset.UTC):to;
        LocalDate start=from==null?end.minusDays(days-1):from;
        if(start.isAfter(end)||java.time.temporal.ChronoUnit.DAYS.between(start,end)>366)throw new ApiException(HttpStatus.BAD_REQUEST,"DATE_RANGE","日期范围无效");
        return db.query("SELECT * FROM vehicle_daily_statistics WHERE vehicle_id=? AND day_utc BETWEEN ? AND ? ORDER BY day_utc",
            (r,n)->new Day(r.getDate("day_utc").toLocalDate(),r.getBigDecimal("distance_km"),r.getBigDecimal("driving_kwh"),r.getBigDecimal("charging_kwh"),r.getBigDecimal("charging_cost"),r.getInt("trip_count"),r.getInt("charge_count")),vehicleId,java.sql.Date.valueOf(start),java.sql.Date.valueOf(end));
    }
    @GetMapping("/statistics/monthly")
    public List<Map<String,Object>> monthly(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId){
        vehicles.owned(sessions.require(auth),vehicleId);
        return db.queryForList("SELECT DATE_FORMAT(day_utc,'%Y-%m') AS month,SUM(distance_km) AS distanceKm,SUM(driving_kwh) AS drivingKwh,SUM(charging_kwh) AS chargingKwh,SUM(charging_cost) AS chargingCost FROM vehicle_daily_statistics WHERE vehicle_id=? GROUP BY DATE_FORMAT(day_utc,'%Y-%m') ORDER BY month DESC LIMIT 12",vehicleId);
    }
    @GetMapping("/statistics/overview")
    public Map<String,Object> overview(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId){
        vehicles.owned(sessions.require(auth),vehicleId);
        return db.queryForMap("SELECT COALESCE(SUM(distance_km),0) AS distanceKm,COALESCE(SUM(driving_kwh),0) AS drivingKwh,COALESCE(SUM(charging_kwh),0) AS chargingKwh,COALESCE(SUM(charging_cost),0) AS chargingCost,COALESCE(SUM(trip_count),0) AS tripCount,COALESCE(SUM(charge_count),0) AS chargeCount FROM vehicle_daily_statistics WHERE vehicle_id=?",vehicleId);
    }
    private void checkPage(int page,int size){if(page<0||size<1||size>100)throw new ApiException(HttpStatus.BAD_REQUEST,"PAGE","分页参数无效");}
    private static Instant instant(Timestamp t){return t==null?null:t.toInstant();}
}
