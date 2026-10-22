package cn.voltmate.vehicle;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VehicleCapabilityServiceTest {
    @Test void missingScopeCannotCommand(){
        var value=VehicleCapabilityService.classify("UNLOCK","vehicle_device_data","PAIRED","2026.20",false);
        assertThat(value.state()).isEqualTo(VehicleCapabilityDTO.State.PERMISSION_REQUIRED);
    }
    @Test void missingKeyCannotCommand(){
        var value=VehicleCapabilityService.classify("LOCK","vehicle_cmds","UNPAIRED","2026.20",false);
        assertThat(value.state()).isEqualTo(VehicleCapabilityDTO.State.KEY_PAIRING_REQUIRED);
    }
    @Test void scheduleRequiresNewFirmware(){
        var value=VehicleCapabilityService.classify("ADD_CHARGE_SCHEDULE","vehicle_charging_cmds","PAIRED","2024.20.6",false);
        assertThat(value.state()).isEqualTo(VehicleCapabilityDTO.State.UNSUPPORTED);
    }
    @Test void modelNameAloneDoesNotProveSupport(){
        var value=VehicleCapabilityService.classify("UNLOCK","vehicle_cmds","PAIRED","2026.20",false);
        assertThat(value.state()).isEqualTo(VehicleCapabilityDTO.State.UNKNOWN);
    }
    @Test void confirmedCommandIsSupported(){
        var value=VehicleCapabilityService.classify("UNLOCK","vehicle_cmds","PAIRED","2026.20",true);
        assertThat(value.state()).isEqualTo(VehicleCapabilityDTO.State.SUPPORTED);
    }
}
