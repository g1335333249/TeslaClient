package cn.voltmate.settings;

import cn.voltmate.auth.AppSessions;
import cn.voltmate.core.*;
import cn.voltmate.vehicle.VehicleService;
import cn.voltmate.history.MockHistory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SettingsController {
    public record Settings(int retentionDays,boolean locationEnabled,int sampleIntervalSeconds,Boolean collectionEnabled){}
    public record Tariff(long id,String name,BigDecimal pricePerKwh,String startsLocal,String endsLocal,String timezone,int dayMask,LocalDate validFrom,LocalDate validTo,int priority){}
    public record TariffInput(String name,BigDecimal pricePerKwh,String startsLocal,String endsLocal,String timezone,Integer dayMask,LocalDate validFrom,LocalDate validTo,Integer priority){}
    private final AppSessions sessions;private final VehicleService vehicles;private final JdbcTemplate db;private final Ids ids;private final MockHistory mockHistory;
    public SettingsController(AppSessions sessions,VehicleService vehicles,JdbcTemplate db,Ids ids,MockHistory mockHistory){this.sessions=sessions;this.vehicles=vehicles;this.db=db;this.ids=ids;this.mockHistory=mockHistory;}
    @GetMapping("/settings")
    public Settings get(@RequestHeader("Authorization") String auth){
        long user=sessions.require(auth);var rows=db.query("SELECT COALESCE(s.retention_days,90),COALESCE(s.location_enabled,TRUE),COALESCE(s.sample_interval_seconds,60),u.collection_paused FROM users u LEFT JOIN user_settings s ON s.user_id=u.id WHERE u.id=?",
            (r,n)->new Settings(r.getInt(1),r.getBoolean(2),r.getInt(3),!r.getBoolean(4)),user);
        return rows.isEmpty()?new Settings(90,true,60,true):rows.getFirst();
    }
    @PutMapping("/settings")
    @Transactional
    public Settings put(@RequestHeader("Authorization") String auth,@RequestBody Settings settings){
        long user=sessions.require(auth);
        if(settings.retentionDays()<7||settings.retentionDays()>3650||settings.sampleIntervalSeconds()<30||settings.sampleIntervalSeconds()>3600)
            throw new ApiException(HttpStatus.BAD_REQUEST,"SETTINGS_RANGE","保留天数或采样间隔超出范围");
        db.update("INSERT INTO user_settings(user_id,retention_days,location_enabled,sample_interval_seconds,updated_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE retention_days=VALUES(retention_days),location_enabled=VALUES(location_enabled),sample_interval_seconds=VALUES(sample_interval_seconds),updated_at=VALUES(updated_at)",
            user,settings.retentionDays(),settings.locationEnabled(),settings.sampleIntervalSeconds());
        boolean wasEnabled=Boolean.TRUE.equals(get(auth).collectionEnabled());
        boolean enabled=settings.collectionEnabled()==null?wasEnabled:settings.collectionEnabled();
        db.update("UPDATE users SET collection_paused=? WHERE id=?",!enabled,user);
        if(enabled&&!wasEnabled)mockHistory.seed();
        if(!settings.locationEnabled()){
            for(var row:db.queryForList("SELECT vehicle_id FROM vehicle_user_access WHERE user_id=?",user))
                deleteLocation(auth,((Number)row.get("vehicle_id")).longValue());
        }
        return new Settings(settings.retentionDays(),settings.locationEnabled(),settings.sampleIntervalSeconds(),enabled);
    }
    @GetMapping("/settings/tariffs")
    public List<Tariff> tariffs(@RequestHeader("Authorization") String auth){
        long user=sessions.require(auth);
        return db.query("SELECT * FROM charging_tariffs WHERE user_id=? ORDER BY starts_local",
            (r,n)->new Tariff(r.getLong("id"),r.getString("name"),r.getBigDecimal("price_per_kwh"),r.getTime("starts_local").toString(),r.getTime("ends_local").toString(),r.getString("timezone"),r.getInt("day_mask"),r.getDate("valid_from")==null?null:r.getDate("valid_from").toLocalDate(),r.getDate("valid_to")==null?null:r.getDate("valid_to").toLocalDate(),r.getInt("priority")),user);
    }
    @PostMapping("/settings/tariffs")
    public Tariff addTariff(@RequestHeader("Authorization") String auth,@RequestBody TariffInput input){
        long user=sessions.require(auth);
        if(input.name()==null||input.name().isBlank()||input.name().length()>80||input.pricePerKwh()==null||input.pricePerKwh().signum()<0||input.pricePerKwh().compareTo(new BigDecimal("100"))>0)
            throw new ApiException(HttpStatus.BAD_REQUEST,"TARIFF_INVALID","电价配置无效");
        java.time.LocalTime start,end;
        try{start=java.time.LocalTime.parse(input.startsLocal());end=java.time.LocalTime.parse(input.endsLocal());java.time.ZoneId.of(input.timezone());}
        catch(Exception e){throw new ApiException(HttpStatus.BAD_REQUEST,"TARIFF_TIME","时间段或时区无效");}
        int mask=input.dayMask()==null?127:input.dayMask(),priority=input.priority()==null?0:input.priority();
        if(mask<1||mask>127||priority<0||priority>1000||input.validFrom()!=null&&input.validTo()!=null&&input.validFrom().isAfter(input.validTo()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"TARIFF_RANGE","电价日期或星期无效");
        long id=ids.next();
        db.update("INSERT INTO charging_tariffs(id,user_id,name,price_per_kwh,starts_local,ends_local,timezone,day_mask,valid_from,valid_to,priority,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))",
            id,user,input.name(),input.pricePerKwh(),java.sql.Time.valueOf(start),java.sql.Time.valueOf(end),input.timezone(),mask,input.validFrom(),input.validTo(),priority);
        return new Tariff(id,input.name(),input.pricePerKwh(),start.toString(),end.toString(),input.timezone(),mask,input.validFrom(),input.validTo(),priority);
    }
    @DeleteMapping("/settings/tariffs/{id}")
    public Map<String,String> deleteTariff(@RequestHeader("Authorization") String auth,@PathVariable long id){
        int n=db.update("DELETE FROM charging_tariffs WHERE id=? AND user_id=?",id,sessions.require(auth));
        if(n==0)throw new ApiException(HttpStatus.NOT_FOUND,"TARIFF_NOT_FOUND","电价不存在");
        return Map.of("status","deleted");
    }
    @DeleteMapping("/vehicles/{vehicleId}/location-history")
    @Transactional
    public Map<String,String> deleteLocation(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId){
        long user=sessions.require(auth);vehicles.owned(user,vehicleId);
        Integer users=db.queryForObject("SELECT COUNT(*) FROM vehicle_user_access WHERE vehicle_id=?",Integer.class,vehicleId);
        if(users!=null&&users>1)throw new ApiException(HttpStatus.CONFLICT,"SHARED_VEHICLE","共享车辆需先解除其他用户访问");
        db.update("DELETE p FROM driving_trip_points p JOIN driving_trips t ON t.id=p.trip_id WHERE t.vehicle_id=?",vehicleId);
        db.update("UPDATE driving_trips SET start_lat=NULL,start_lon=NULL,end_lat=NULL,end_lon=NULL,completeness='NO_LOCATION' WHERE vehicle_id=?",vehicleId);
        db.update("UPDATE vehicle_status_latest SET latitude=NULL,longitude=NULL WHERE vehicle_id=?",vehicleId);
        db.update("DELETE FROM vehicle_telemetry_samples WHERE vehicle_id=? AND signal_name IN ('Location','OriginLocation','DestinationLocation','DestinationName','RouteLine','GpsState','GpsHeading')",vehicleId);
        db.update("DELETE FROM vehicle_signal_latest WHERE vehicle_id=? AND signal_name IN ('Location','OriginLocation','DestinationLocation','DestinationName','RouteLine','GpsState','GpsHeading')",vehicleId);
        db.update("DELETE FROM vehicle_telemetry_raw WHERE vehicle_id=? AND SUBSTRING_INDEX(topic,'/',-1) IN ('Location','OriginLocation','DestinationLocation','DestinationName','RouteLine','GpsState','GpsHeading')",vehicleId);
        return Map.of("status","deleted");
    }
}
