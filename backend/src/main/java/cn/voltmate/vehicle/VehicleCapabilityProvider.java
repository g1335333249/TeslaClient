package cn.voltmate.vehicle;

import com.fasterxml.jackson.databind.JsonNode;

public interface VehicleCapabilityProvider {
    JsonNode fleetStatus(String vin, long accountId);
}
