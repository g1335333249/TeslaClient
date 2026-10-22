package cn.voltmate.vehicle;

import java.math.BigDecimal;
import java.time.Instant;

public final class VehicleDto {
    private VehicleDto(){}
    public record Vehicle(long id,String vin,String name,String model,String color,String firmware,String keyState,String region){}
    public record Status(long vehicleId,String source,Instant observedAt,Instant receivedAt,String onlineState,
        BigDecimal soc,BigDecimal rangeKm,BigDecimal odometerKm,BigDecimal insideC,BigDecimal outsideC,
        Boolean climateOn,Boolean locked,String chargingState,BigDecimal chargingKw,Integer chargeLimit,
        BigDecimal latitude,BigDecimal longitude,boolean mock){}
}
