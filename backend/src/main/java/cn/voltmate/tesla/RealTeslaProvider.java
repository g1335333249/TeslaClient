package cn.voltmate.tesla;

import cn.voltmate.core.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RealTeslaProvider implements TeslaProvider {
    private static final Map<String,String> PATHS=Map.ofEntries(
        Map.entry("LOCK","door_lock"),Map.entry("UNLOCK","door_unlock"),Map.entry("FLASH","flash_lights"),Map.entry("HONK","honk_horn"),
        Map.entry("CLIMATE_ON","auto_conditioning_start"),Map.entry("CLIMATE_OFF","auto_conditioning_stop"),Map.entry("SET_TEMPERATURE","set_temps"),
        Map.entry("FRUNK","actuate_trunk"),Map.entry("TRUNK","actuate_trunk"),Map.entry("CHARGE_START","charge_start"),Map.entry("CHARGE_STOP","charge_stop"),
        Map.entry("SET_CHARGE_LIMIT","set_charge_limit"));
    private final JdbcTemplate db;private final TeslaConfig config;private final TeslaHttp http;private final TeslaTokens tokens;
    public RealTeslaProvider(JdbcTemplate db,TeslaConfig config,TeslaHttp http,TeslaTokens tokens){this.db=db;this.config=config;this.http=http;this.tokens=tokens;}
    @Override public void execute(long id,String type,Integer value){
        var row=db.queryForMap("SELECT v.vin,v.account_id,a.scopes FROM vehicles v JOIN tesla_accounts a ON a.id=v.account_id WHERE v.id=?",id);
        String required=type.startsWith("CHARGE")||type.equals("SET_CHARGE_LIMIT")?"vehicle_charging_cmds":"vehicle_cmds";
        if(!Arrays.asList(((String)row.get("scopes")).split(" ")).contains(required)) throw new ApiException(HttpStatus.FORBIDDEN,"SCOPE_REQUIRED","Tesla 未授予该指令权限");
        String body=switch(type){
            case "FRUNK" -> "{\"which_trunk\":\"front\"}";
            case "TRUNK" -> "{\"which_trunk\":\"rear\"}";
            case "SET_CHARGE_LIMIT" -> "{\"percent\":"+value+"}";
            case "SET_TEMPERATURE" -> "{\"driver_temp\":"+value+",\"passenger_temp\":"+value+"}";
            default -> "{}";
        };
        var response=http.post(config.proxyUrl+"/api/1/vehicles/"+row.get("vin")+"/command/"+PATHS.get(type),
            tokens.access(((Number)row.get("account_id")).longValue()),body);
        if(!response.path("response").path("result").asBoolean(false)) throw new ApiException(HttpStatus.BAD_GATEWAY,"COMMAND_REJECTED","车辆未确认指令");
    }
}
