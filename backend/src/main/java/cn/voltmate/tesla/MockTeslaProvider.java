package cn.voltmate.tesla;

import cn.voltmate.core.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class MockTeslaProvider implements TeslaProvider {
    private final JdbcTemplate db;
    public MockTeslaProvider(JdbcTemplate db){this.db=db;}
    @Override public void execute(long id,String type,Integer value){
        String fault=db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.mockFault')) FROM vehicle_status_latest WHERE vehicle_id=?",String.class,id);
        if("COMMAND_FAILURE".equals(fault))throw new ApiException(HttpStatus.BAD_GATEWAY,"MOCK_COMMAND_FAILURE","演示车辆拒绝指令");
        if("COMMAND_TIMEOUT".equals(fault))throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,"MOCK_COMMAND_TIMEOUT","演示车辆指令超时");
        switch(type){
            case "LOCK" -> db.update("UPDATE vehicle_status_latest SET locked=TRUE,observed_at=UTC_TIMESTAMP(6) WHERE vehicle_id=?",id);
            case "UNLOCK" -> db.update("UPDATE vehicle_status_latest SET locked=FALSE,observed_at=UTC_TIMESTAMP(6) WHERE vehicle_id=?",id);
            case "CLIMATE_ON" -> db.update("UPDATE vehicle_status_latest SET climate_on=TRUE,observed_at=UTC_TIMESTAMP(6) WHERE vehicle_id=?",id);
            case "CLIMATE_OFF" -> db.update("UPDATE vehicle_status_latest SET climate_on=FALSE,observed_at=UTC_TIMESTAMP(6) WHERE vehicle_id=?",id);
            case "CHARGE_START" -> db.update("UPDATE vehicle_status_latest SET charging_state='CHARGING',charging_kw=7,observed_at=UTC_TIMESTAMP(6) WHERE vehicle_id=?",id);
            case "CHARGE_STOP" -> db.update("UPDATE vehicle_status_latest SET charging_state='STOPPED',charging_kw=0,observed_at=UTC_TIMESTAMP(6) WHERE vehicle_id=?",id);
            case "SET_CHARGE_LIMIT" -> db.update("UPDATE vehicle_status_latest SET charge_limit=?,observed_at=UTC_TIMESTAMP(6) WHERE vehicle_id=?",value,id);
            case "SET_TEMPERATURE" -> db.update("UPDATE vehicle_status_latest SET inside_c=?,observed_at=UTC_TIMESTAMP(6) WHERE vehicle_id=?",value,id);
            default -> {}
        }
    }
}
