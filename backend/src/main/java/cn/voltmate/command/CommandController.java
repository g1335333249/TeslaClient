package cn.voltmate.command;

import cn.voltmate.auth.AppSessions;
import cn.voltmate.core.*;
import cn.voltmate.tesla.*;
import cn.voltmate.vehicle.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicles/{vehicleId}/commands")
public class CommandController {
    public record Request(String type,Integer value,String idempotencyKey){}
    public record Result(long id,String type,String outcome,String message){}
    private static final Set<String> COMMANDS=Set.of("LOCK","UNLOCK","FLASH","HONK","CLIMATE_ON","CLIMATE_OFF","SET_TEMPERATURE","FRUNK","TRUNK","CHARGE_START","CHARGE_STOP","SET_CHARGE_LIMIT");
    private final AppSessions sessions;private final VehicleService vehicles;private final VehicleCapabilityService capabilities;private final TeslaConfig config;private final MockTeslaProvider mock;private final RealTeslaProvider real;private final JdbcTemplate db;private final Ids ids;private final VehicleEvents events;
    public CommandController(AppSessions sessions,VehicleService vehicles,VehicleCapabilityService capabilities,TeslaConfig config,MockTeslaProvider mock,RealTeslaProvider real,JdbcTemplate db,Ids ids,VehicleEvents events){
        this.sessions=sessions;this.vehicles=vehicles;this.capabilities=capabilities;this.config=config;this.mock=mock;this.real=real;this.db=db;this.ids=ids;this.events=events;
    }
    @PostMapping
    @Transactional
    public Result send(@RequestHeader("Authorization") String auth,@PathVariable long vehicleId,@RequestBody Request request){
        long user=sessions.require(auth);vehicles.owned(user,vehicleId);
        if(request==null||request.type()==null||!COMMANDS.contains(request.type())) throw new ApiException(HttpStatus.BAD_REQUEST,"COMMAND_TYPE","不支持该指令");
        if(request.idempotencyKey()==null||request.idempotencyKey().length()<12||request.idempotencyKey().length()>100) throw new ApiException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY","请提供唯一请求键");
        if(request.type().equals("SET_CHARGE_LIMIT")&&(request.value()==null||request.value()<50||request.value()>100)) throw new ApiException(HttpStatus.BAD_REQUEST,"VALUE_RANGE","充电上限应为 50–100");
        if(request.type().equals("SET_TEMPERATURE")&&(request.value()==null||request.value()<16||request.value()>30)) throw new ApiException(HttpStatus.BAD_REQUEST,"VALUE_RANGE","温度应为 16–30°C");
        var previous=db.queryForList("SELECT id,command_type,outcome FROM vehicle_command_logs WHERE user_id=? AND vehicle_id=? AND idempotency_key=?",user,vehicleId,request.idempotencyKey());
        if(!previous.isEmpty()){
            var first=previous.getFirst();
            if(!request.type().equals(first.get("command_type"))) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","请求键已用于其他指令");
            return new Result(((Number)first.get("id")).longValue(),request.type(),(String)first.get("outcome"),"重复请求，返回原结果");
        }
        Integer recent=db.queryForObject("SELECT COUNT(*) FROM vehicle_command_logs WHERE user_id=? AND vehicle_id=? AND created_at>UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE",Integer.class,user,vehicleId);
        if(recent!=null&&recent>=10) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"COMMAND_RATE_LIMIT","指令过于频繁");
        capabilities.requireCommand(user,vehicleId,request.type());
        var status=vehicles.status(user,vehicleId);
        if(!"ONLINE".equals(status.onlineState())) throw new ApiException(HttpStatus.CONFLICT,"VEHICLE_OFFLINE","车辆离线或休眠");
        var vehicle=vehicles.one(user,vehicleId);
        if(!"PAIRED".equals(vehicle.keyState())) throw new ApiException(HttpStatus.CONFLICT,"KEY_NOT_PAIRED","请先完成虚拟钥匙配对");
        long id=ids.next();
        db.update("INSERT INTO vehicle_command_logs(id,vehicle_id,user_id,idempotency_key,command_type,outcome,created_at) VALUES(?,?,?,?,?,'PENDING',?)",
            id,vehicleId,user,request.idempotencyKey(),request.type(),Timestamp.from(Instant.now()));
        try {
            TeslaProvider provider=config.mode.equals("mock")?mock:real;
            provider.execute(vehicleId,request.type(),request.value());
            db.update("UPDATE vehicle_command_logs SET outcome='SUCCESS',completed_at=UTC_TIMESTAMP(6) WHERE id=?",id);
            publishAfterCommit(vehicleId,new Result(id,request.type(),"SUCCESS","车辆已确认"));
            return new Result(id,request.type(),"SUCCESS",config.mode.equals("mock")?"演示指令已执行":"Tesla 已确认指令");
        }catch(ApiException e){
            db.update("UPDATE vehicle_command_logs SET outcome='FAILED',error_code=?,completed_at=UTC_TIMESTAMP(6) WHERE id=?",e.code,id);
            publishAfterCommit(vehicleId,new Result(id,request.type(),"FAILED",e.getMessage()));
            return new Result(id,request.type(),"FAILED",e.getMessage());
        }catch(RuntimeException e){
            db.update("UPDATE vehicle_command_logs SET outcome='FAILED',error_code='COMMAND_INTERNAL_ERROR',completed_at=UTC_TIMESTAMP(6) WHERE id=?",id);
            publishAfterCommit(vehicleId,new Result(id,request.type(),"FAILED","指令未得到车辆确认"));
            return new Result(id,request.type(),"FAILED","指令未得到车辆确认，请检查车辆状态");
        }
    }
    private void publishAfterCommit(long vehicleId,Result result){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
        @Override public void afterCommit(){events.publish(vehicleId,"COMMAND_RESULT",result);}
    });}
}
