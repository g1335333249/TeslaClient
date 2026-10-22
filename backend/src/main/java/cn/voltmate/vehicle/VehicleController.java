package cn.voltmate.vehicle;

import cn.voltmate.auth.AppSessions;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {
    private final VehicleService vehicles;private final AppSessions sessions;
    public VehicleController(VehicleService vehicles,AppSessions sessions){this.vehicles=vehicles;this.sessions=sessions;}
    @GetMapping public List<VehicleDto.Vehicle> list(@RequestHeader("Authorization") String auth){return vehicles.list(sessions.require(auth));}
    @PostMapping("/sync") public List<VehicleDto.Vehicle> sync(@RequestHeader("Authorization") String auth){long user=sessions.require(auth);vehicles.syncReal(user);return vehicles.list(user);}
    @GetMapping("/{id}") public VehicleDto.Vehicle one(@RequestHeader("Authorization") String auth,@PathVariable long id){return vehicles.one(sessions.require(auth),id);}
    @GetMapping("/{id}/status") public VehicleDto.Status status(@RequestHeader("Authorization") String auth,@PathVariable long id){return vehicles.status(sessions.require(auth),id);}
    @GetMapping("/{id}/location") public Map<String,Object> location(@RequestHeader("Authorization") String auth,@PathVariable long id){
        var s=vehicles.status(sessions.require(auth),id);Map<String,Object> out=new HashMap<>();
        out.put("latitude",s.latitude());out.put("longitude",s.longitude());out.put("observedAt",s.observedAt());out.put("source",s.source());return out;
    }
    @GetMapping("/{id}/capabilities") public Map<String,Object> capabilities(@RequestHeader("Authorization") String auth,@PathVariable long id){return vehicles.capabilities(sessions.require(auth),id);}
}
