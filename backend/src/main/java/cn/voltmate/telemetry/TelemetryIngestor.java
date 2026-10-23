package cn.voltmate.telemetry;

import cn.voltmate.core.Crypto;
import cn.voltmate.core.Ids;
import cn.voltmate.core.SqlTime;
import cn.voltmate.vehicle.VehicleEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TelemetryIngestor {
    private static final Set<String> LOCATION=Set.of("Location","OriginLocation","DestinationLocation","DestinationName","RouteLine","GpsState","GpsHeading");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Ids ids;
    private final Crypto crypto;
    private final TelemetryAggregator aggregator;
    private final VehicleEvents events;
    public TelemetryIngestor(JdbcTemplate db,ObjectMapper json,Ids ids,Crypto crypto,TelemetryAggregator aggregator,VehicleEvents events){
        this.db=db;this.json=json;this.ids=ids;this.crypto=crypto;this.aggregator=aggregator;this.events=events;
    }
    @Transactional
    public void ingest(String topic,String payload){
        if(topic==null||payload==null||payload.length()>100_000)return;
        String[] parts=topic.split("/");
        if(parts.length<3||!"telemetry".equals(parts[0]))return;
        var rows=db.queryForList("SELECT v.id FROM vehicles v LEFT JOIN tesla_accounts a ON a.id=v.account_id WHERE v.vin=? AND (v.account_id IS NULL OR a.authorization_state='ACTIVE')",parts[1]);
        if(rows.isEmpty())return;
        long vehicle=((Number)rows.getFirst().get("id")).longValue();
        var subscriptions=db.queryForList("SELECT u.collection_paused FROM users u JOIN vehicle_user_access a ON a.user_id=u.id WHERE a.vehicle_id=? FOR SHARE",vehicle);
        if(subscriptions.isEmpty()||subscriptions.stream().allMatch(r->{Object paused=r.get("collection_paused");return Boolean.TRUE.equals(paused)||paused instanceof Number n&&n.intValue()!=0;}))return;
        if(parts.length==4&&LOCATION.contains(parts[3])){
            Integer disabled=db.queryForObject("SELECT COUNT(*) FROM vehicle_user_access a JOIN user_settings s ON s.user_id=a.user_id WHERE a.vehicle_id=? AND s.location_enabled=FALSE",Integer.class,vehicle);
            if(disabled!=null&&disabled>0)return;
        }
        Instant received=Instant.now(),observed=received;
        String eventId=null,quality="RECEIVED_ONLY";
        JsonNode parsed;
        try{parsed=json.readTree(payload);}catch(Exception e){parsed=json.getNodeFactory().textNode(payload);}
        JsonNode value=parsed;
        if(parsed.isObject()&&parsed.has("eventId")&&parsed.has("observedAt")&&parsed.has("value")){
            eventId=parsed.path("eventId").asText(null);
            value=parsed.path("value");
            try{observed=Instant.parse(parsed.path("observedAt").asText());quality="SOURCE_TIME";}
            catch(Exception e){quality="INVALID";}
        }else if(parts[2].equals("connectivity")&&parsed.hasNonNull("CreatedAt")){
            try{observed=Instant.parse(parsed.path("CreatedAt").asText());quality="SOURCE_TIME";}
            catch(Exception e){quality="INVALID";}
        }
        String key=crypto.hash(eventId==null||eventId.isBlank()
            ?topic+"\n"+payload+"\n"+received.getEpochSecond()/5
            :parts[1]+"\n"+eventId);
        long rawId=ids.next();
        String state=quality.equals("INVALID")||observed.isAfter(received.plusSeconds(300))||observed.isBefore(received.minusSeconds(366L*86400))
            ?"INVALID_TIMESTAMP":"RECEIVED";
        try{
            int added=db.update("INSERT IGNORE INTO vehicle_telemetry_raw(id,vehicle_id,event_key,topic,event_type,observed_at,received_at,ingested_at,payload,processing_state,timestamp_quality) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                rawId,vehicle,key,topic,parts[2],Timestamp.from(observed),Timestamp.from(received),Timestamp.from(Instant.now()),
                json.writeValueAsString(parsed),state,quality);
            if(added==0)return;
        }catch(Exception e){throw new IllegalStateException("Telemetry raw persistence failed",e);}
        if(state.equals("INVALID_TIMESTAMP"))return;
        String observedText=observed.toString();
        try{
            if(parts[2].equals("v")&&parts.length==4){
                String signal=parts[3];
                BigDecimal number=null;
                try{if(value.isNumber()||value.isTextual())number=new BigDecimal(value.asText());}catch(NumberFormatException ignored){}
                db.update("INSERT INTO vehicle_telemetry_samples(id,vehicle_id,signal_name,numeric_value,text_value,observed_at,received_at,source) VALUES(?,?,?,?,?,?,?,'TELEMETRY')",
                    ids.next(),vehicle,signal,number,value.isValueNode()?value.asText():null,Timestamp.from(observed),Timestamp.from(received));
                boolean latest=acceptLatest(vehicle,signal,observed,received,rawId);
                String column=switch(signal){case "BatteryLevel"->"soc";case "InsideTemp"->"inside_c";case "OutsideTemp"->"outside_c";case "ACChargingPower","DCChargingPower"->"charging_kw";case "ChargeLimitSoc"->"charge_limit";default->null;};
                if(latest&&column!=null&&number!=null)db.update("UPDATE vehicle_status_latest SET "+column+"=?,source='TELEMETRY',observed_at=GREATEST(COALESCE(observed_at,?),?),received_at=?,updated_at=? WHERE vehicle_id=?",
                    number,Timestamp.from(observed),Timestamp.from(observed),Timestamp.from(received),Timestamp.from(Instant.now()),vehicle);
                aggregator.signal(vehicle,signal,value,observed,latest);
                if(latest){String eventType=signal.contains("Charging")||signal.equals("ChargeState")?"CHARGING_CHANGED":"STATUS_CHANGED";
                    afterCommit(()->events.publish(vehicle,eventType,Map.of("signal",signal,"observedAt",observedText)));}
            }else if(parts[2].equals("connectivity")){
                String online=value.path("Status").asText("").equalsIgnoreCase("connected")?"ONLINE":"ASLEEP";
                if(acceptLatest(vehicle,"Connectivity",observed,received,rawId))
                    db.update("UPDATE vehicle_status_latest SET online_state=?,source='TELEMETRY',observed_at=GREATEST(COALESCE(observed_at,?),?),received_at=?,updated_at=? WHERE vehicle_id=?",
                        online,Timestamp.from(observed),Timestamp.from(observed),Timestamp.from(received),Timestamp.from(Instant.now()),vehicle);
                afterCommit(()->events.publish(vehicle,"STATUS_CHANGED",Map.of("signal","Connectivity","observedAt",observedText)));
            }else if(parts[2].equals("errors")){
                String name=value.path("Name").asText("遥测错误"),detail=value.path("Body").asText("车辆报告遥测连接异常");
                for(var access:db.queryForList("SELECT user_id FROM vehicle_user_access WHERE vehicle_id=?",vehicle))
                    db.update("INSERT INTO notification_records(id,user_id,vehicle_id,type,title,body,created_at) VALUES(?,?,?,'TELEMETRY_ERROR',?,?,?)",
                        ids.next(),access.get("user_id"),vehicle,name.substring(0,Math.min(100,name.length())),detail.substring(0,Math.min(450,detail.length())),Timestamp.from(received));
            }
            db.update("UPDATE vehicle_telemetry_raw SET processing_state='PROCESSED' WHERE id=?",rawId);
            db.update("INSERT INTO telemetry_ingestion_offsets(consumer_name,topic,last_event_key,last_received_at,updated_at) VALUES('backend',?,?,?,?) ON DUPLICATE KEY UPDATE last_event_key=VALUES(last_event_key),last_received_at=VALUES(last_received_at),updated_at=VALUES(updated_at)",
                topic,key,Timestamp.from(received),Timestamp.from(Instant.now()));
        }catch(Exception e){
            throw new IllegalStateException("Telemetry processing failed; MQTT acknowledgment withheld",e);
        }
    }
    private static void afterCommit(Runnable run){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){run.run();}});}
    private boolean acceptLatest(long vehicle,String signal,Instant observed,Instant received,long rawId){
        int inserted=db.update("INSERT IGNORE INTO vehicle_signal_latest(vehicle_id,signal_name,observed_at,received_at,raw_id) VALUES(?,?,?,?,?)",
            vehicle,signal,Timestamp.from(observed),Timestamp.from(received),rawId);
        if(inserted==1)return true;
        var row=db.queryForMap("SELECT observed_at FROM vehicle_signal_latest WHERE vehicle_id=? AND signal_name=? FOR UPDATE",vehicle,signal);
        if(!observed.isAfter(SqlTime.instant(row.get("observed_at"))))return false;
        db.update("UPDATE vehicle_signal_latest SET observed_at=?,received_at=?,raw_id=? WHERE vehicle_id=? AND signal_name=?",
            Timestamp.from(observed),Timestamp.from(received),rawId,vehicle,signal);
        return true;
    }
}
