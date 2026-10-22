package cn.voltmate.vehicle;

import cn.voltmate.tesla.TeslaConfig;
import cn.voltmate.tesla.TeslaHttp;
import cn.voltmate.tesla.TeslaTokens;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class RealVehicleCapabilityProvider implements VehicleCapabilityProvider {
    private final TeslaConfig config;
    private final TeslaHttp http;
    private final TeslaTokens tokens;
    public RealVehicleCapabilityProvider(TeslaConfig config,TeslaHttp http,TeslaTokens tokens){this.config=config;this.http=http;this.tokens=tokens;}
    @Override public JsonNode fleetStatus(String vin,long accountId){
        return http.post(config.fleetBase()+"/api/1/vehicles/fleet_status",tokens.access(accountId),"{\"vins\":[\""+vin+"\"]}");
    }
}
