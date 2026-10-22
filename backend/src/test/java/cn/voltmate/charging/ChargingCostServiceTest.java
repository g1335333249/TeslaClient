package cn.voltmate.charging;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ChargingCostServiceTest {
    @Test void overnightTariffUsesPreviousDayValidityAndMask(){
        var night=new ChargingCostService.Tariff("谷时",new BigDecimal("0.45"),LocalTime.of(23,0),LocalTime.of(7,0),ZoneId.of("Asia/Shanghai"),2,LocalDate.of(2026,10,19),LocalDate.of(2026,10,19),1);
        var morning=Instant.parse("2026-10-19T22:00:00Z");
        assertThat(ChargingCostService.tariffAt(List.of(night),morning)).isEqualTo(night);
        assertThat(ChargingCostService.tariffAt(List.of(night),morning.plusSeconds(86400))).isNull();
    }
    @Test void tariffBoundaryDoesNotApplyEndMinute(){
        var day=new ChargingCostService.Tariff("峰时",BigDecimal.ONE,LocalTime.of(7,0),LocalTime.of(23,0),ZoneId.of("Asia/Shanghai"),127,null,null,1);
        assertThat(ChargingCostService.tariffAt(List.of(day),Instant.parse("2026-10-20T14:59:00Z"))).isEqualTo(day);
        assertThat(ChargingCostService.tariffAt(List.of(day),Instant.parse("2026-10-20T15:00:00Z"))).isNull();
    }
}
