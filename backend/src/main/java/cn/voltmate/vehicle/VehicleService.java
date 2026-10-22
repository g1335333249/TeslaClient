package cn.voltmate.vehicle;

import cn.voltmate.core.*;
import cn.voltmate.tesla.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VehicleService {
    private final JdbcTemplate db;private final TeslaConfig config;private final TeslaHttp http;private final TeslaTokens tokens;private final Ids ids;private final VehicleRepository repository;
    public VehicleService(JdbcTemplate db,TeslaConfig config,TeslaHttp http,TeslaTokens tokens,Ids ids,VehicleRepository repository){this.db=db;this.config=config;this.http=http;this.tokens=tokens;this.ids=ids;this.repository=repository;}
    public void owned(long user,long id){
        Integer n=db.queryForObject("SELECT COUNT(*) FROM vehicle_user_access WHERE vehicle_id=? AND user_id=?",Integer.class,id,user);
        if(n==null||n==0) throw new ApiException(HttpStatus.NOT_FOUND,"VEHICLE_NOT_FOUND","车辆不存在");
    }
    public List<VehicleDto.Vehicle> list(long user){
        return db.query("SELECT v.* FROM vehicles v JOIN vehicle_user_access a ON a.vehicle_id=v.id WHERE a.user_id=? ORDER BY v.id",
            (r,n)->new VehicleDto.Vehicle(r.getLong("id"),r.getString("vin"),r.getString("display_name"),r.getString("model"),r.getString("color"),r.getString("firmware"),r.getString("key_state"),r.getString("region")),user);
    }
    public VehicleDto.Vehicle one(long user,long id){
        owned(user,id);
        var v=repository.findById(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"VEHICLE_NOT_FOUND","车辆不存在"));
        return new VehicleDto.Vehicle(v.getId(),v.getVin(),v.getDisplayName(),v.getModel(),v.getColor(),v.getFirmware(),v.getKeyState(),v.getRegion());
    }
    public VehicleDto.Status status(long user,long id){
        owned(user,id);
        var rows=db.query("SELECT * FROM vehicle_status_latest WHERE vehicle_id=?", (r,n)->new VehicleDto.Status(id,r.getString("source"),
            stamp(r.getTimestamp("observed_at")),stamp(r.getTimestamp("received_at")),r.getString("online_state"),
            r.getBigDecimal("soc"),r.getBigDecimal("range_km"),r.getBigDecimal("odometer_km"),r.getBigDecimal("inside_c"),r.getBigDecimal("outside_c"),
            (Boolean)r.getObject("climate_on"),(Boolean)r.getObject("locked"),r.getString("charging_state"),r.getBigDecimal("charging_kw"),(Integer)r.getObject("charge_limit"),
            r.getBigDecimal("latitude"),r.getBigDecimal("longitude"),config.mode.equals("mock")),id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"STATUS_UNAVAILABLE","暂无车辆状态");
        return rows.getFirst();
    }
    public void seedMock(){
        if(!config.mode.equals("mock")) return;
        db.update("INSERT IGNORE INTO users(id,display_name,created_at) VALUES(1001,'演示用户',UTC_TIMESTAMP(6))");
        for(int i=0;i<2;i++){
            long id=2001+i;String vin="MOCKVIN000000000"+i;
            db.update("INSERT IGNORE INTO vehicles(id,vin,display_name,model,color,firmware,region,key_state,created_at) VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))",
                id,vin,i==0?"小白":"小灰",i==0?"Model Y L":"Model 3","珍珠白","2026.20","CN","PAIRED");
            db.update("INSERT IGNORE INTO vehicle_user_access(vehicle_id,user_id,access_level,created_at) VALUES(?,?,?,UTC_TIMESTAMP(6))",id,1001,"OWNER");
            db.update("INSERT IGNORE INTO vehicle_status_latest(vehicle_id,source,observed_at,received_at,updated_at,online_state,soc,range_km,odometer_km,inside_c,outside_c,climate_on,locked,charging_state,charging_kw,charge_limit,latitude,longitude) VALUES(?,'MOCK',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'ONLINE',?,?,?,?,?,FALSE,TRUE,'DISCONNECTED',0,80,31.230416,121.473701)",
                id,i==0?76:58,i==0?420:310,i==0?12800:8200,24,19);
        }
    }
    public void tickMock(){
        if(!config.mode.equals("mock")) return;
        db.update("UPDATE vehicle_status_latest SET online_state=IF(MOD(MINUTE(UTC_TIMESTAMP()),10)>=7,'ASLEEP','ONLINE') WHERE vehicle_id=2002");
        db.update("UPDATE vehicle_status_latest SET soc=LEAST(100,soc+0.2),charging_kw=7.0,observed_at=UTC_TIMESTAMP(6),received_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE charging_state='CHARGING'");
        db.update("UPDATE vehicle_status_latest SET soc=GREATEST(5,soc-0.01),range_km=GREATEST(0,range_km-0.05),observed_at=UTC_TIMESTAMP(6),received_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE online_state='ONLINE' AND charging_state<>'CHARGING'");
    }
    public void syncReal(long user){
        if(!config.mode.equals("real")) throw new ApiException(HttpStatus.BAD_REQUEST,"REAL_MODE_REQUIRED","仅真实模式可同步");
        var accounts=db.queryForList("SELECT id FROM tesla_accounts WHERE user_id=? AND authorization_state='ACTIVE'",user);
        for(var account:accounts){
            long accountId=((Number)account.get("id")).longValue();
            var response=http.get(config.fleetBase()+"/api/1/vehicles",tokens.access(accountId));
            for(var item:response.path("response")){
                String vin=item.path("vin").asText();
                if(vin.isBlank()) continue;
                long id=ids.next();
                db.update("INSERT INTO vehicles(id,account_id,vin,display_name,model,region,key_state,created_at) VALUES(?,?,?,?,? ,?,'UNKNOWN',UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE display_name=VALUES(display_name)",
                    id,accountId,vin,item.path("display_name").asText("Tesla"),item.path("vehicle_config").path("car_type").asText(null),config.region);
                Long actual=db.queryForObject("SELECT id FROM vehicles WHERE vin=?",Long.class,vin);
                db.update("INSERT IGNORE INTO vehicle_user_access(vehicle_id,user_id,access_level,created_at) VALUES(?,?,'OWNER',UTC_TIMESTAMP(6))",actual,user);
                db.update("INSERT IGNORE INTO vehicle_status_latest(vehicle_id,source,updated_at,online_state) VALUES(?,'CACHE',UTC_TIMESTAMP(6),?)",
                    actual,item.path("state").asText("UNKNOWN").equals("online")?"ONLINE":"ASLEEP");
            }
        }
    }
    private static Instant stamp(Timestamp t){return t==null?null:t.toInstant();}
}
