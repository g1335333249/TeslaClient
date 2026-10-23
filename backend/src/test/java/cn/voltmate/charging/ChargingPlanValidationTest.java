package cn.voltmate.charging;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargingPlanValidationTest {
    @Test void acceptsAllDaysAndSelectedWeekdays() {
        assertThatCode(() -> ChargingPlanController.validate(input("ALL"))).doesNotThrowAnyException();
        assertThatCode(() -> ChargingPlanController.validate(input("MON,WED,FRI"))).doesNotThrowAnyException();
    }

    @Test void rejectsMalformedDays() {
        assertThatThrownBy(() -> ChargingPlanController.validate(input("MON,,FRI")))
            .isInstanceOf(cn.voltmate.core.ApiException.class);
    }

    private static ChargingPlanController.Input input(String days) {
        return new ChargingPlanController.Input(new BigDecimal("31.23"), new BigDecimal("121.47"),
            1380, 420, true, true, days, true, false);
    }
}
