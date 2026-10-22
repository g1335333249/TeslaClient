package cn.voltmate.history;

import cn.voltmate.core.Ids;
import cn.voltmate.tesla.TeslaConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Component
public class MockHistory {
    private final JdbcTemplate db;private final Ids ids;private final TeslaConfig config;
    public MockHistory(JdbcTemplate db,Ids ids,TeslaConfig config){this.db=db;this.ids=ids;this.config=config;}
    @Order(2)
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void seed(){
        if(!config.mode.equals("mock"))return;
        for(long vehicle=2001;vehicle<=2002;vehicle++){
            Integer enabled=db.queryForObject("SELECT COUNT(*) FROM vehicle_user_access a JOIN users u ON u.id=a.user_id WHERE a.vehicle_id=? AND u.collection_paused=FALSE",Integer.class,vehicle);
            if(enabled==null||enabled==0)continue;
            Integer count=db.queryForObject("SELECT COUNT(*) FROM vehicle_daily_statistics WHERE vehicle_id=?",Integer.class,vehicle);
            if(count!=null&&count>0)continue;
            Integer locationOff=db.queryForObject("SELECT COUNT(*) FROM user_settings s JOIN vehicle_user_access a ON a.user_id=s.user_id WHERE a.vehicle_id=? AND s.location_enabled=FALSE",Integer.class,vehicle);
            boolean withLocation=locationOff==null||locationOff==0;
            for(int d=1;d<=30;d++){
                LocalDate day=LocalDate.now(ZoneOffset.UTC).minusDays(d);
                Instant start=day.atTime(8,30).toInstant(ZoneOffset.UTC),end=start.plusSeconds(1800+d*20);
                long trip=ids.next();BigDecimal distance=BigDecimal.valueOf(12+d%10);
                if(withLocation){
                    db.update("INSERT INTO driving_trips(id,vehicle_id,started_at,ended_at,start_lat,start_lon,end_lat,end_lon,distance_km,energy_kwh,completeness) VALUES(?,?,?,?,31.230416,121.473701,31.240416,121.503701,?,?,'COMPLETE')",
                        trip,vehicle,Timestamp.from(start),Timestamp.from(end),distance,BigDecimal.valueOf(2.5+d%4));
                    for(int p=0;p<8;p++)db.update("INSERT INTO driving_trip_points(id,trip_id,observed_at,latitude,longitude) VALUES(?,?,?,?,?)",
                        ids.next(),trip,Timestamp.from(start.plusSeconds(p*240)),BigDecimal.valueOf(31.230416+p*0.001428),BigDecimal.valueOf(121.473701+p*0.004285));
                }else db.update("INSERT INTO driving_trips(id,vehicle_id,started_at,ended_at,distance_km,energy_kwh,completeness) VALUES(?,?,?,?,?,?,'NO_LOCATION')",
                    trip,vehicle,Timestamp.from(start),Timestamp.from(end),distance,BigDecimal.valueOf(2.5+d%4));
                long charge=ids.next();
                db.update("INSERT INTO charging_sessions(id,vehicle_id,started_at,ended_at,start_soc,end_soc,grid_kwh,battery_kwh,estimated_cost) VALUES(?,?,?,?,?,?,?,?,?)",
                    charge,vehicle,Timestamp.from(start.plusSeconds(36000)),Timestamp.from(start.plusSeconds(46800)),40,80,BigDecimal.valueOf(24),BigDecimal.valueOf(21),BigDecimal.valueOf(13.20));
                db.update("INSERT INTO vehicle_daily_statistics(vehicle_id,day_utc,distance_km,driving_kwh,charging_kwh,charging_cost,trip_count,charge_count,updated_at) VALUES(?,?,?,?,?,?,1,1,UTC_TIMESTAMP(6))",
                    vehicle,java.sql.Date.valueOf(day),distance,BigDecimal.valueOf(2.5+d%4),BigDecimal.valueOf(24),BigDecimal.valueOf(13.20));
            }
        }
    }
}
