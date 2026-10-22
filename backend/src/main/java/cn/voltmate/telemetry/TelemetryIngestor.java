package cn.voltmate.telemetry;

import cn.voltmate.core.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryIngestor {
    private final JdbcTemplate db;private final ObjectMapper json;private final Ids ids;private final Crypto crypto;private final TelemetryAggregator aggregator;
    public TelemetryIngestor(JdbcTemplate db,ObjectMapper json,Ids ids,Crypto crypto,TelemetryAggregator aggregator){this.db=db;this.json=json;this.ids=ids;this.crypto=crypto;this.aggregator=aggregator;}
    @Transactional
    public void ingest(String topic,String payload){
        if(topic==null||payload==null||payload.length()>100_000)return;
        String[] parts=topic.split("/");
        if(parts.length<3||!parts[0].equals("telemetry"))return;
        var vehicles=db.queryForList("SELECT v.id FROM vehicles v LEFT JOIN tesla_accounts a ON a.id=v.account_id WHERE v.vin=? AND (v.account_id IS NULL OR a.authorization_state='ACTIVE')",parts[1]);
        if(vehicles.isEmpty())return;
        long vehicle=((Number)vehicles.getFirst().get("id")).longValue();
        if(parts.length==4&&java.util.Set.of("Location","OriginLocation","DestinationLocation","DestinationName","RouteLine","GpsState","GpsHeading").contains(parts[3])){
            Integer disabled=db.queryForObject("SELECT COUNT(*) FROM vehicle_user_access a JOIN user_settings s ON s.user_id=a.user_id WHERE a.vehicle_id=? AND s.location_enabled=FALSE",Integer.class,vehicle);
            if(disabled!=null&&disabled>0)return;
        }
        try{
            JsonNode value=json.readTree(payload);Instant now=Instant.now();
            String eventKey=crypto.hash(topic+"\n"+payload+"\n"+(now.getEpochSecond()/2));
            int inserted=db.update("INSERT IGNORE INTO vehicle_telemetry_raw(id,vehicle_id,event_key,topic,event_type,received_at,payload) VALUES(?,?,?,?,?,?,?)",
                ids.next(),vehicle,eventKey,topic,parts[2],Timestamp.from(now),payload);
            if(inserted==0)return;
            if(parts[2].equals("v")&&parts.length==4){
                String signal=parts[3];
                BigDecimal number=null;
                try{if(value.isNumber()||value.isTextual())number=new BigDecimal(value.asText());}catch(NumberFormatException ignored){}
                db.update("INSERT INTO vehicle_telemetry_samples(id,vehicle_id,signal_name,numeric_value,text_value,observed_at,received_at,source) VALUES(?,?,?,?,?,?,?,'TELEMETRY')",
                    ids.next(),vehicle,signal,number,value.isValueNode()?value.asText():null,Timestamp.from(now),Timestamp.from(now));
                String column=switch(signal){case "BatteryLevel"->"soc";case "InsideTemp"->"inside_c";case "OutsideTemp"->"outside_c";case "ACChargingPower"->"charging_kw";case "ChargeLimitSoc"->"charge_limit";default->null;};
                if(column!=null&&number!=null)db.update("UPDATE vehicle_status_latest SET "+column+"=?,source='TELEMETRY',observed_at=?,received_at=?,updated_at=? WHERE vehicle_id=? AND (observed_at IS NULL OR observed_at<=?)",
                    number,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),vehicle,Timestamp.from(now));
                aggregator.signal(vehicle,signal,value,now);
            }else if(parts[2].equals("connectivity")){
                String state=value.path("Status").asText("");
                String online=state.equalsIgnoreCase("connected")?"ONLINE":"ASLEEP";
                db.update("UPDATE vehicle_status_latest SET online_state=?,source='TELEMETRY',observed_at=?,received_at=?,updated_at=? WHERE vehicle_id=?",
                    online,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),vehicle);
            }else if(parts[2].equals("errors")){
                String name=value.path("Name").asText("遥测错误");
                String detail=value.path("Body").asText("车辆报告遥测连接异常");
                if(name.length()>100)name=name.substring(0,100);
                if(detail.length()>450)detail=detail.substring(0,450);
                for(var access:db.queryForList("SELECT user_id FROM vehicle_user_access WHERE vehicle_id=?",vehicle)){
                    db.update("INSERT INTO notification_records(id,user_id,vehicle_id,type,title,body,created_at) VALUES(?,?,?,'TELEMETRY_ERROR',?,?,?)",
                        ids.next(),access.get("user_id"),vehicle,name,detail,Timestamp.from(now));
                }
            }
            db.update("INSERT INTO telemetry_ingestion_offsets(consumer_name,topic,last_event_key,last_received_at,updated_at) VALUES('backend',?,?,?,?) ON DUPLICATE KEY UPDATE last_event_key=VALUES(last_event_key),last_received_at=VALUES(last_received_at),updated_at=VALUES(updated_at)",
                topic,eventKey,Timestamp.from(now),Timestamp.from(now));
        }catch(Exception e){throw new IllegalStateException("Telemetry ingestion failed",e);}
    }
}
