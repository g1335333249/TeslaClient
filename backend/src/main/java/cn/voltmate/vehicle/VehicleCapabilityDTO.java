package cn.voltmate.vehicle;

import java.time.Instant;
import java.util.Map;

public record VehicleCapabilityDTO(
    long vehicleId, String model, String firmwareVersion, String telemetryVersion,
    Boolean commandProtocolRequired, String keyState, String scopes,
    Map<String,Capability> capabilities, String pairingUrl, Instant checkedAt,
    boolean mock
) {
    public record Capability(State state, String reason) {}
    public enum State { SUPPORTED, UNSUPPORTED, PERMISSION_REQUIRED, KEY_PAIRING_REQUIRED, UNKNOWN }
}
