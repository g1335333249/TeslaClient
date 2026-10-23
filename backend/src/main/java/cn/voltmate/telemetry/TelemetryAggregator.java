package cn.voltmate.telemetry;

import cn.voltmate.core.Ids;
import cn.voltmate.core.SqlTime;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TelemetryAggregator {
    private final JdbcTemplate db;private final Ids ids;
    public TelemetryAggregator(JdbcTemplate db,Ids ids){this.db=db;this.ids=ids;}
    public void signal(long vehicle,String name,JsonNode value,Instant at,boolean latest){
        switch(name){
            case "VehicleSpeed" -> {if(latest)speed(vehicle,value,at);}
            case "Location" -> location(vehicle,value,at,latest);
            case "Odometer" -> {if(latest)odometer(vehicle,value,at);}
            case "ACChargingPower","DCChargingPower" -> {if(latest)chargingPower(vehicle,value,at);}
            case "ACChargingEnergyIn" -> chargingEnergy(vehicle,value,at,latest,true);
            case "DCChargingEnergyIn" -> chargingEnergy(vehicle,value,at,latest,false);
            case "BatteryLevel" -> {if(latest)battery(vehicle,value);}
            case "Locked" -> {if(latest)db.update("UPDATE vehicle_status_latest SET locked=? WHERE vehicle_id=?",value.asBoolean(),vehicle);}
            case "RatedRange" -> {
                double miles=asDouble(value);
                if(latest&&Double.isFinite(miles)&&miles>=0)db.update("UPDATE vehicle_status_latest SET range_km=? WHERE vehicle_id=?",BigDecimal.valueOf(miles*1.609344),vehicle);
            }
            case "ChargeState" -> {if(latest)db.update("UPDATE vehicle_status_latest SET charging_state=? WHERE vehicle_id=?",value.asText().toUpperCase(java.util.Locale.ROOT),vehicle);}
            default -> {}
        }
    }
    private void speed(long vehicle,JsonNode value,Instant at){
        double mph=asDouble(value);
        if(!Double.isFinite(mph))return;
        var open=db.queryForList("SELECT id FROM driving_trips WHERE vehicle_id=? AND ended_at IS NULL ORDER BY started_at DESC LIMIT 1",vehicle);
        if(mph>2.0){
            if(open.isEmpty()){
                var status=db.queryForList("SELECT odometer_km,latitude,longitude FROM vehicle_status_latest WHERE vehicle_id=?",vehicle);
                var row=status.isEmpty()?java.util.Map.<String,Object>of():status.getFirst();
                db.update("INSERT INTO driving_trips(id,vehicle_id,started_at,start_lat,start_lon,start_odometer_km,last_motion_at,completeness) VALUES(?,?,?,?,?,?,?,'IN_PROGRESS')",
                    ids.next(),vehicle,Timestamp.from(at),row.get("latitude"),row.get("longitude"),row.get("odometer_km"),Timestamp.from(at));
            }else db.update("UPDATE driving_trips SET last_motion_at=? WHERE id=?",Timestamp.from(at),open.getFirst().get("id"));
        }
    }
    private void location(long vehicle,JsonNode value,Instant at,boolean latest){
        JsonNode lat=value.has("latitude")?value.get("latitude"):value.get("Latitude");
        JsonNode lon=value.has("longitude")?value.get("longitude"):value.get("Longitude");
        if(lat==null||lon==null)return;
        double latitude=asDouble(lat),longitude=asDouble(lon);
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||Math.abs(latitude)>90||Math.abs(longitude)>180)return;
        if(latest)db.update("UPDATE vehicle_status_latest SET latitude=?,longitude=?,source='TELEMETRY',observed_at=GREATEST(COALESCE(observed_at,?),?),received_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE vehicle_id=?",
            latitude,longitude,Timestamp.from(at),Timestamp.from(at),vehicle);
        var open=db.queryForList("SELECT id FROM driving_trips WHERE vehicle_id=? AND started_at<=? AND (ended_at IS NULL OR ended_at>=?) ORDER BY started_at DESC LIMIT 1",vehicle,Timestamp.from(at),Timestamp.from(at));
        if(!open.isEmpty()){
            long trip=((Number)open.getFirst().get("id")).longValue();
            db.update("INSERT INTO driving_trip_points(id,trip_id,observed_at,latitude,longitude) VALUES(?,?,?,?,?)",ids.next(),trip,Timestamp.from(at),latitude,longitude);
            db.update("UPDATE driving_trips t SET start_lat=(SELECT p.latitude FROM driving_trip_points p WHERE p.trip_id=t.id ORDER BY p.observed_at,p.id LIMIT 1),start_lon=(SELECT p.longitude FROM driving_trip_points p WHERE p.trip_id=t.id ORDER BY p.observed_at,p.id LIMIT 1),end_lat=(SELECT p.latitude FROM driving_trip_points p WHERE p.trip_id=t.id ORDER BY p.observed_at DESC,p.id DESC LIMIT 1),end_lon=(SELECT p.longitude FROM driving_trip_points p WHERE p.trip_id=t.id ORDER BY p.observed_at DESC,p.id DESC LIMIT 1),completeness=IF(ended_at IS NULL,'IN_PROGRESS',IF((SELECT COUNT(*) FROM driving_trip_points p WHERE p.trip_id=t.id)>=2,'COMPLETE','NO_LOCATION')) WHERE t.id=?",trip);
        }
    }
    private void odometer(long vehicle,JsonNode value,Instant at){
        double miles=asDouble(value);
        if(!Double.isFinite(miles)||miles<0)return;
        BigDecimal km=BigDecimal.valueOf(miles*1.609344);
        db.update("UPDATE vehicle_status_latest SET odometer_km=?,source='TELEMETRY',observed_at=?,received_at=?,updated_at=? WHERE vehicle_id=?",
            km,Timestamp.from(at),Timestamp.from(at),Timestamp.from(at),vehicle);
        db.update("UPDATE driving_trips SET distance_km=GREATEST(0,?-start_odometer_km) WHERE vehicle_id=? AND ended_at IS NULL AND start_odometer_km IS NOT NULL",
            km,vehicle);
    }
    private void chargingPower(long vehicle,JsonNode value,Instant at){
        double kw=asDouble(value);
        if(!Double.isFinite(kw)||kw<0)return;
        var open=db.queryForList("SELECT id FROM charging_sessions WHERE vehicle_id=? AND ended_at IS NULL ORDER BY started_at DESC LIMIT 1",vehicle);
        if(kw>0.2){
            db.update("UPDATE vehicle_status_latest SET charging_state='CHARGING',charging_kw=? WHERE vehicle_id=?",kw,vehicle);
            if(open.isEmpty()){
                BigDecimal soc=db.queryForObject("SELECT soc FROM vehicle_status_latest WHERE vehicle_id=?",BigDecimal.class,vehicle);
                db.update("INSERT INTO charging_sessions(id,vehicle_id,started_at,start_soc,last_power_at) VALUES(?,?,?,?,?)",
                    ids.next(),vehicle,Timestamp.from(at),soc,Timestamp.from(at));
            }else db.update("UPDATE charging_sessions SET last_power_at=? WHERE id=?",Timestamp.from(at),open.getFirst().get("id"));
        }else db.update("UPDATE vehicle_status_latest SET charging_state='STOPPED',charging_kw=0 WHERE vehicle_id=?",vehicle);
    }
    private void chargingEnergy(long vehicle,JsonNode value,Instant at,boolean latest,boolean grid){
        double kwh=asDouble(value);
        if(!Double.isFinite(kwh)||kwh<0)return;
        var sessions=db.queryForList("SELECT id FROM charging_sessions WHERE vehicle_id=? AND started_at<=? AND (ended_at IS NULL OR ended_at>=?) ORDER BY started_at DESC LIMIT 1",vehicle,Timestamp.from(at),Timestamp.from(at));
        if(sessions.isEmpty())return;
        long session=((Number)sessions.getFirst().get("id")).longValue();
        db.update("INSERT IGNORE INTO charging_energy_samples(id,session_id,observed_at,grid_kwh_total,battery_kwh_total,source) VALUES(?,?,?,?,?,?)",
            ids.next(),session,Timestamp.from(at),grid?BigDecimal.valueOf(kwh):null,grid?null:BigDecimal.valueOf(kwh),grid?"GRID":"BATTERY");
        if(latest)db.update("UPDATE charging_sessions SET "+(grid?"grid_kwh":"battery_kwh")+"=? WHERE id=?",BigDecimal.valueOf(kwh),session);
    }
    private void battery(long vehicle,JsonNode value){
        double soc=asDouble(value);
        if(!Double.isFinite(soc)||soc<0||soc>100)return;
        db.update("UPDATE charging_sessions SET end_soc=? WHERE vehicle_id=? AND ended_at IS NULL",soc,vehicle);
    }
    @Scheduled(fixedDelay=60000)
    @Transactional
    public void closeIdle(){
        var trips=db.queryForList("SELECT id,vehicle_id,started_at,last_motion_at,distance_km FROM driving_trips WHERE ended_at IS NULL AND last_motion_at<UTC_TIMESTAMP(6)-INTERVAL 10 MINUTE");
        for(var t:trips){
            long id=((Number)t.get("id")).longValue(),vehicle=((Number)t.get("vehicle_id")).longValue();
            int points=db.queryForObject("SELECT COUNT(*) FROM driving_trip_points WHERE trip_id=?",Integer.class,id);
            String completeness=points>=2?"COMPLETE":"NO_LOCATION";
            int closed=db.update("UPDATE driving_trips SET ended_at=last_motion_at,completeness=? WHERE id=? AND ended_at IS NULL",completeness,id);
            if(closed==0)continue;
            BigDecimal km=(BigDecimal)t.get("distance_km");
            allocateTripDaily(vehicle,SqlTime.instant(t.get("started_at")),SqlTime.instant(t.get("last_motion_at")),km==null?BigDecimal.ZERO:km);
        }
        var charging=db.queryForList("SELECT id,vehicle_id,started_at,grid_kwh FROM charging_sessions WHERE ended_at IS NULL AND last_power_at<UTC_TIMESTAMP(6)-INTERVAL 5 MINUTE");
        for(var c:charging){
            long id=((Number)c.get("id")).longValue(),vehicle=((Number)c.get("vehicle_id")).longValue();
            if(db.update("UPDATE charging_sessions SET ended_at=last_power_at WHERE id=? AND ended_at IS NULL",id)==0)continue;
            BigDecimal kwh=(BigDecimal)c.get("grid_kwh");
            java.sql.Date day=java.sql.Date.valueOf(SqlTime.instant(c.get("started_at")).atZone(java.time.ZoneOffset.UTC).toLocalDate());
            db.update("INSERT INTO vehicle_daily_statistics(vehicle_id,day_utc,charging_kwh,charge_count,updated_at) VALUES(?,?,?,1,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE charging_kwh=charging_kwh+VALUES(charging_kwh),charge_count=charge_count+1,updated_at=VALUES(updated_at)",
                vehicle,day,kwh==null?BigDecimal.ZERO:kwh);
        }
    }
    private void allocateTripDaily(long vehicle,Instant start,Instant end,BigDecimal km){
        if(!end.isAfter(start)){addDay(vehicle,start,km,1);return;}
        long total=java.time.Duration.between(start,end).toMillis();BigDecimal assigned=BigDecimal.ZERO;
        for(Instant cursor=start;cursor.isBefore(end);){
            Instant boundary=cursor.atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            Instant next=boundary.isBefore(end)?boundary:end;
            BigDecimal portion=next.equals(end)?km.subtract(assigned):km.multiply(BigDecimal.valueOf(java.time.Duration.between(cursor,next).toMillis()))
                .divide(BigDecimal.valueOf(total),3,java.math.RoundingMode.HALF_UP);
            addDay(vehicle,cursor,portion,cursor.equals(start)?1:0);assigned=assigned.add(portion);cursor=next;
        }
    }
    private void addDay(long vehicle,Instant at,BigDecimal km,int count){
        java.sql.Date day=java.sql.Date.valueOf(at.atZone(java.time.ZoneOffset.UTC).toLocalDate());
        db.update("INSERT INTO vehicle_daily_statistics(vehicle_id,day_utc,distance_km,trip_count,updated_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE distance_km=distance_km+VALUES(distance_km),trip_count=trip_count+VALUES(trip_count),updated_at=VALUES(updated_at)",vehicle,day,km,count);
    }
    private static double asDouble(JsonNode n){try{return Double.parseDouble(n.asText());}catch(Exception e){return Double.NaN;}}
}
