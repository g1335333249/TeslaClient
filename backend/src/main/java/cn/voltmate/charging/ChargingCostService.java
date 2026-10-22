package cn.voltmate.charging;

import cn.voltmate.core.*;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChargingCostService {
    public record Cost(long sessionId,BigDecimal amount,String currency,String accuracy,String source,Map<String,BigDecimal> tariffBreakdown,Map<String,BigDecimal> dailyBreakdown){}
    public record Tariff(String name,BigDecimal price,LocalTime start,LocalTime end,ZoneId zone,int dayMask,LocalDate from,LocalDate to,int priority){}
    private record EnergyPoint(Instant at,BigDecimal total){}
    private final JdbcTemplate db;
    public ChargingCostService(JdbcTemplate db){this.db=db;}
    public Cost calculate(long user,long sessionId){
        var manual=db.queryForList("SELECT manual_amount FROM charging_session_costs WHERE user_id=? AND session_id=? AND manual_amount IS NOT NULL",user,sessionId);
        if(!manual.isEmpty())return new Cost(sessionId,(BigDecimal)manual.getFirst().get("manual_amount"),"CNY","ACTUAL","MANUAL",Map.of(),Map.of());
        var rows=db.queryForList("SELECT * FROM charging_sessions WHERE id=?",sessionId);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"CHARGE_NOT_FOUND","充电记录不存在");
        var row=rows.getFirst();Instant start=((Timestamp)row.get("started_at")).toInstant();
        Instant end=row.get("ended_at")==null?Instant.now():((Timestamp)row.get("ended_at")).toInstant();
        BigDecimal energy=(BigDecimal)row.get("grid_kwh");String source="GRID";
        if(energy==null){energy=(BigDecimal)row.get("estimated_kwh");source="ESTIMATED_ENERGY";}
        if(energy==null){energy=(BigDecimal)row.get("battery_kwh");source="BATTERY_DELTA";}
        if(energy==null||!end.isAfter(start))return new Cost(sessionId,null,"CNY","UNAVAILABLE","MISSING_DATA",Map.of(),Map.of());
        var tariffs=tariffs(user);
        if(tariffs.isEmpty())return new Cost(sessionId,null,"CNY","UNAVAILABLE","NO_TARIFF",Map.of(),Map.of());
        BigDecimal total=BigDecimal.ZERO;boolean intervals=false;Map<String,BigDecimal> breakdown=new TreeMap<>(),daily=new TreeMap<>();
        if(source.equals("GRID")){
            var points=db.query("SELECT observed_at,grid_kwh_total FROM charging_energy_samples WHERE session_id=? AND source='GRID' ORDER BY observed_at",
                (r,n)->new EnergyPoint(r.getTimestamp(1).toInstant(),r.getBigDecimal(2)),sessionId);
            if(points.size()>=2){
                BigDecimal sum=BigDecimal.ZERO;Instant previousTime=start;BigDecimal previousEnergy=BigDecimal.ZERO;boolean valid=true;
                for(var point:points){
                    if(point.at().isBefore(previousTime)||point.total().compareTo(previousEnergy)<0){valid=false;break;}
                    BigDecimal delta=point.total().subtract(previousEnergy);
                    BigDecimal part=prorate(previousTime,point.at(),delta,tariffs,breakdown,daily);
                    if(part==null){valid=false;break;}
                    total=total.add(part);sum=sum.add(delta);previousTime=point.at();previousEnergy=point.total();
                }
                intervals=valid&&sum.subtract(energy).abs().compareTo(new BigDecimal("0.05"))<=0;
            }
        }
        if(!intervals){breakdown.clear();daily.clear();total=prorate(start,end,energy,tariffs,breakdown,daily);source=source+"_UNIFORM";}
        if(total==null)return new Cost(sessionId,null,"CNY","UNAVAILABLE","TARIFF_GAP",Map.of(),Map.of());
        breakdown.replaceAll((k,v)->v.setScale(2,RoundingMode.HALF_UP));
        daily.replaceAll((k,v)->v.setScale(2,RoundingMode.HALF_UP));
        return new Cost(sessionId,total.setScale(2,RoundingMode.HALF_UP),"CNY","ESTIMATED",intervals?"GRID_INTERVALS":source,breakdown,daily);
    }
    public void save(long user,Cost cost){
        db.update("INSERT INTO charging_session_costs(session_id,user_id,amount,currency,accuracy,source,calculated_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE amount=VALUES(amount),currency=VALUES(currency),accuracy=VALUES(accuracy),source=VALUES(source),calculated_at=VALUES(calculated_at)",
            cost.sessionId(),user,cost.amount(),cost.currency(),cost.accuracy(),cost.source());
    }
    public Cost manual(long user,long sessionId,BigDecimal amount){
        if(amount==null||amount.signum()<0||amount.compareTo(new BigDecimal("1000000"))>0)throw new ApiException(HttpStatus.BAD_REQUEST,"COST_INVALID","费用无效");
        var cost=new Cost(sessionId,amount.setScale(2,RoundingMode.HALF_UP),"CNY","ACTUAL","MANUAL",Map.of(),Map.of());
        db.update("INSERT INTO charging_session_costs(session_id,user_id,amount,currency,accuracy,source,manual_amount,calculated_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE amount=VALUES(amount),accuracy='ACTUAL',source='MANUAL',manual_amount=VALUES(manual_amount),calculated_at=VALUES(calculated_at)",
            sessionId,user,cost.amount(),"CNY","ACTUAL","MANUAL",cost.amount());return cost;
    }
    private List<Tariff> tariffs(long user){return db.query("SELECT * FROM charging_tariffs WHERE user_id=? ORDER BY priority DESC,id DESC",(r,n)->new Tariff(r.getString("name"),r.getBigDecimal("price_per_kwh"),r.getTime("starts_local").toLocalTime(),r.getTime("ends_local").toLocalTime(),ZoneId.of(r.getString("timezone")),r.getInt("day_mask"),r.getDate("valid_from")==null?null:r.getDate("valid_from").toLocalDate(),r.getDate("valid_to")==null?null:r.getDate("valid_to").toLocalDate(),r.getInt("priority")),user);}
    static Tariff tariffAt(List<Tariff> tariffs,Instant at){
        for(Tariff t:tariffs){var local=at.atZone(t.zone());LocalTime time=local.toLocalTime();LocalDate day=local.toLocalDate();
            boolean overnight=!t.end().isAfter(t.start());boolean afterStart=!time.isBefore(t.start());boolean beforeEnd=time.isBefore(t.end());
            if(!(overnight?(afterStart||beforeEnd):(afterStart&&beforeEnd)))continue;
            if(overnight&&beforeEnd)day=day.minusDays(1);
            int bit=1<<(day.getDayOfWeek().getValue()%7);
            if((t.dayMask()&bit)!=0&&(t.from()==null||!day.isBefore(t.from()))&&(t.to()==null||!day.isAfter(t.to())))return t;
        }return null;
    }
    private static BigDecimal prorate(Instant start,Instant end,BigDecimal energy,List<Tariff> tariffs,Map<String,BigDecimal> breakdown,Map<String,BigDecimal> daily){
        long duration=Duration.between(start,end).toSeconds();if(duration<=0)return energy.signum()==0?BigDecimal.ZERO:null;
        BigDecimal total=BigDecimal.ZERO;
        for(Instant cursor=start;cursor.isBefore(end);){
            Instant next=cursor.plusSeconds(60);if(next.isAfter(end))next=end;
            Tariff tariff=tariffAt(tariffs,cursor);if(tariff==null)return null;
            BigDecimal fraction=BigDecimal.valueOf(Duration.between(cursor,next).toSeconds()).divide(BigDecimal.valueOf(duration),12,RoundingMode.HALF_UP);
            BigDecimal cost=energy.multiply(fraction).multiply(tariff.price());
            total=total.add(cost);breakdown.merge(tariff.name(),cost,BigDecimal::add);
            daily.merge(cursor.atZone(tariff.zone()).toLocalDate().toString(),cost,BigDecimal::add);cursor=next;
        }return total;
    }
}
