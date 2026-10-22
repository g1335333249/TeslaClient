package cn.voltmate.vehicle;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Component
public class MockTick {
    private final VehicleService service;
    public MockTick(VehicleService service){this.service=service;}
    @Order(1)
    @EventListener(ApplicationReadyEvent.class) public void seed(){service.seedMock();}
    @Scheduled(fixedDelay=60000) public void tick(){service.tickMock();}
}
