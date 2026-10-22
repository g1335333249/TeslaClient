package cn.voltmate.vehicle;

import cn.voltmate.auth.AppSessions;
import cn.voltmate.core.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.socket.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class VehicleEventsTest {
    @Test void rejectsSubscriptionToAnotherUsersVehicle() throws Exception {
        AppSessions sessions=mock(AppSessions.class);VehicleService vehicles=mock(VehicleService.class);
        when(sessions.require("Bearer valid")).thenReturn(42L);
        doThrow(new ApiException(HttpStatus.NOT_FOUND,"VEHICLE_NOT_FOUND","车辆不存在")).when(vehicles).owned(42L,7L);
        var handler=new VehicleEvents(sessions,vehicles,new ObjectMapper());
        WebSocketSession socket=mock(WebSocketSession.class);when(socket.isOpen()).thenReturn(true);
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"AUTH\",\"token\":\"valid\"}"));
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"SUBSCRIBE\",\"vehicleId\":7}"));
        handler.publish(7,"STATUS_CHANGED",java.util.Map.of("soc",50));
        var messages=org.mockito.ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(socket,times(2)).sendMessage(messages.capture());
        assertThat(messages.getAllValues().get(1).getPayload().toString()).contains("VEHICLE_ACCESS_DENIED");
    }
    @Test void subscriptionReceivesOnlyAuthorizedVehicle() throws Exception {
        AppSessions sessions=mock(AppSessions.class);VehicleService vehicles=mock(VehicleService.class);
        when(sessions.require("Bearer valid")).thenReturn(42L);
        var handler=new VehicleEvents(sessions,vehicles,new ObjectMapper());
        WebSocketSession socket=mock(WebSocketSession.class);when(socket.isOpen()).thenReturn(true);
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"AUTH\",\"token\":\"valid\"}"));
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"SUBSCRIBE\",\"vehicleId\":7}"));
        handler.publish(8,"STATUS_CHANGED",java.util.Map.of("soc",20));
        handler.publish(7,"CHARGING_CHANGED",java.util.Map.of("state","CHARGING"));
        verify(socket,times(3)).sendMessage(any());
        verify(vehicles,times(2)).owned(42L,7L);
        verify(vehicles,never()).owned(42L,8L);
    }
}
