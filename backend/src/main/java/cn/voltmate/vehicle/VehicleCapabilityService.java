package cn.voltmate.vehicle;

import cn.voltmate.core.ApiException;
import cn.voltmate.tesla.TeslaConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VehicleCapabilityService {
    public static final Set<String> COMMANDS=Set.of("LOCK","UNLOCK","FLASH","HONK","CLIMATE_ON","CLIMATE_OFF",
        "SET_TEMPERATURE","FRUNK","TRUNK","CHARGE_START","CHARGE_STOP","SET_CHARGE_LIMIT","ADD_CHARGE_SCHEDULE","REMOVE_CHARGE_SCHEDULE");
    private final VehicleService vehicles;
    private final JdbcTemplate db;
    private final TeslaConfig config;
    private final RealVehicleCapabilityProvider real;
    private final ObjectMapper json;
    public VehicleCapabilityService(VehicleService vehicles,JdbcTemplate db,TeslaConfig config,RealVehicleCapabilityProvider real,ObjectMapper json){
        this.vehicles=vehicles;this.db=db;this.config=config;this.real=real;this.json=json;
    }
    public VehicleCapabilityDTO get(long user,long vehicleId,boolean refresh){
        vehicles.owned(user,vehicleId);
        var vehicle=vehicles.one(user,vehicleId);
        if(config.mode.equals("mock")){
            Map<String,VehicleCapabilityDTO.Capability> mock=new LinkedHashMap<>();
            for(String command:COMMANDS)mock.put(command,new VehicleCapabilityDTO.Capability(VehicleCapabilityDTO.State.SUPPORTED,"演示车辆能力"));
            return new VehicleCapabilityDTO(vehicleId,vehicle.model(),vehicle.firmware(),"MOCK",true,"PAIRED",
                "vehicle_cmds vehicle_charging_cmds vehicle_device_data vehicle_location",mock,"",Instant.now(),true);
        }
        var rows=db.queryForList("SELECT v.vin,v.account_id,v.model,v.firmware,v.key_state,a.scopes,a.authorization_state FROM vehicles v JOIN tesla_accounts a ON a.id=v.account_id WHERE v.id=? AND a.user_id=?",vehicleId,user);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"VEHICLE_NOT_FOUND","车辆不存在");
        var row=rows.getFirst();
        if(!refresh){
            var cached=db.queryForList("SELECT * FROM vehicle_capability_snapshots WHERE vehicle_id=? AND checked_at>UTC_TIMESTAMP(6)-INTERVAL 5 MINUTE",vehicleId);
            if(!cached.isEmpty())return fromCache(vehicleId,vehicle.region(),cached.getFirst());
        }
        String vin=(String)row.get("vin");
        JsonNode response=real.fleetStatus(vin,((Number)row.get("account_id")).longValue()).path("response");
        JsonNode info=response.path("vehicle_info").path(vin);
        if(info.isMissingNode())for(JsonNode item:response.path("vehicles"))if(vin.equals(item.path("vin").asText())){info=item;break;}
        String keyState=contains(response.path("key_paired_vins"),vin)?"PAIRED":contains(response.path("unpaired_vins"),vin)?"UNPAIRED":"UNKNOWN";
        String firmware=text(info,"firmware_version",(String)row.get("firmware"));
        String telemetry=text(info,"fleet_telemetry_version",null);
        Boolean protocol=info.hasNonNull("vehicle_command_protocol_required")?info.get("vehicle_command_protocol_required").asBoolean():null;
        String scopes=(String)row.get("scopes");
        var confirmed=db.queryForList("SELECT DISTINCT command_type FROM vehicle_command_logs WHERE vehicle_id=? AND outcome='SUCCESS'",vehicleId);
        Set<String> confirmedCommands=confirmed.stream().map(r->(String)r.get("command_type")).collect(java.util.stream.Collectors.toSet());
        Map<String,VehicleCapabilityDTO.Capability> states=new LinkedHashMap<>();
        for(String command:COMMANDS)states.put(command,classify(command,scopes,keyState,firmware,confirmedCommands.contains(command)));
        Instant at=Instant.now();
        try{
            db.update("INSERT INTO vehicle_capability_snapshots(vehicle_id,model,firmware_version,telemetry_version,command_protocol_required,key_state,scopes,capabilities_json,checked_at) VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE model=VALUES(model),firmware_version=VALUES(firmware_version),telemetry_version=VALUES(telemetry_version),command_protocol_required=VALUES(command_protocol_required),key_state=VALUES(key_state),scopes=VALUES(scopes),capabilities_json=VALUES(capabilities_json),checked_at=VALUES(checked_at)",
                vehicleId,vehicle.model(),firmware,telemetry,protocol,keyState,scopes,json.writeValueAsString(states),Timestamp.from(at));
        }catch(Exception e){throw new IllegalStateException("Capability snapshot save failed",e);}
        db.update("UPDATE vehicles SET key_state=?,firmware=COALESCE(?,firmware) WHERE id=?",keyState,firmware,vehicleId);
        return new VehicleCapabilityDTO(vehicleId,vehicle.model(),firmware,telemetry,protocol,keyState,scopes,states,pairingUrl(vehicle.region()),at,false);
    }
    public void requireCommand(long user,long vehicleId,String command){
        if(!COMMANDS.contains(command))throw new ApiException(HttpStatus.BAD_REQUEST,"COMMAND_TYPE","不支持该指令");
        var state=get(user,vehicleId,true).capabilities().get(command);
        switch(state.state()){
            case UNSUPPORTED -> throw new ApiException(HttpStatus.CONFLICT,"COMMAND_UNSUPPORTED",state.reason());
            case PERMISSION_REQUIRED -> throw new ApiException(HttpStatus.FORBIDDEN,"SCOPE_REQUIRED",state.reason());
            case KEY_PAIRING_REQUIRED -> throw new ApiException(HttpStatus.CONFLICT,"KEY_NOT_PAIRED",state.reason());
            default -> {}
        }
    }
    static VehicleCapabilityDTO.Capability classify(String command,String scopes,String keyState,String firmware,boolean confirmed){
        boolean charging=command.startsWith("CHARGE")||command.startsWith("SET_CHARGE")||command.endsWith("CHARGE_SCHEDULE");
        String required=charging?"vehicle_charging_cmds":"vehicle_cmds";
        if(!Arrays.asList(scopes.split(" ")).contains(required))return new VehicleCapabilityDTO.Capability(VehicleCapabilityDTO.State.PERMISSION_REQUIRED,"需要 Tesla 授权范围 "+required);
        if(!"PAIRED".equals(keyState))return new VehicleCapabilityDTO.Capability(VehicleCapabilityDTO.State.KEY_PAIRING_REQUIRED,"需要确认虚拟钥匙配对");
        if(command.endsWith("CHARGE_SCHEDULE")&&firmware!=null&&olderThanScheduleFirmware(firmware))
            return new VehicleCapabilityDTO.Capability(VehicleCapabilityDTO.State.UNSUPPORTED,"固件版本低于 2024.26");
        if(confirmed)return new VehicleCapabilityDTO.Capability(VehicleCapabilityDTO.State.SUPPORTED,"此车辆曾确认执行成功");
        return new VehicleCapabilityDTO.Capability(VehicleCapabilityDTO.State.UNKNOWN,"尚未在此车辆上验证");
    }
    private static boolean olderThanScheduleFirmware(String firmware){
        try{String[] parts=firmware.split("\\.");int year=Integer.parseInt(parts[0]),release=Integer.parseInt(parts[1]);return year<2024||(year==2024&&release<26);}catch(Exception e){return false;}
    }
    private static boolean contains(JsonNode array,String vin){for(JsonNode node:array)if(vin.equals(node.asText()))return true;return false;}
    private static String text(JsonNode node,String key,String fallback){String value=node.path(key).asText("");return value.isBlank()?fallback:value;}
    private String pairingUrl(String region){return "https://"+("CN".equals(region)?"tesla.cn":"tesla.com")+"/_ak/"+config.publicDomain;}
    private VehicleCapabilityDTO fromCache(long id,String region,Map<String,Object> row){
        try{
            var type=json.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,VehicleCapabilityDTO.Capability.class);
            Map<String,VehicleCapabilityDTO.Capability> states=json.readValue((String)row.get("capabilities_json"),type);
            return new VehicleCapabilityDTO(id,(String)row.get("model"),(String)row.get("firmware_version"),(String)row.get("telemetry_version"),
                (Boolean)row.get("command_protocol_required"),(String)row.get("key_state"),(String)row.get("scopes"),states,
                pairingUrl(region),((Timestamp)row.get("checked_at")).toInstant(),false);
        }catch(Exception e){throw new IllegalStateException("Capability snapshot read failed",e);}
    }
}
