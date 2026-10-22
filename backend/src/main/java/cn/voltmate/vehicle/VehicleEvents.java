package cn.voltmate.vehicle;

import cn.voltmate.auth.AppSessions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Configuration
@EnableWebSocket
class VehicleWebSocketConfiguration implements WebSocketConfigurer {
    private final VehicleEvents events;
    VehicleWebSocketConfiguration(VehicleEvents events){this.events=events;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(events,"/api/v1/ws").setAllowedOriginPatterns("*");}
}

@Component
public class VehicleEvents extends TextWebSocketHandler {
    private record Client(long user,Set<Long> vehicles,AtomicLong lastSeen){}
    private final Map<WebSocketSession,Client> clients=new ConcurrentHashMap<>();
    private final Map<WebSocketSession,Long> pending=new ConcurrentHashMap<>();
    private final Map<String,Long> lastSent=new ConcurrentHashMap<>();
    private final AppSessions sessions;private final VehicleService vehicles;private final ObjectMapper json;
    public VehicleEvents(AppSessions sessions,VehicleService vehicles,ObjectMapper json){this.sessions=sessions;this.vehicles=vehicles;this.json=json;}
    @Override public void afterConnectionEstablished(WebSocketSession socket){pending.put(socket,System.currentTimeMillis());}
    @Override protected void handleTextMessage(WebSocketSession socket,TextMessage message) throws Exception {
        var node=json.readTree(message.getPayload());String type=node.path("type").asText();
        if(type.equals("AUTH")){
            try{long user=sessions.require("Bearer "+node.path("token").asText());pending.remove(socket);clients.put(socket,new Client(user,ConcurrentHashMap.newKeySet(),new AtomicLong(System.currentTimeMillis())));send(socket,Map.of("type","AUTH_OK","at",Instant.now().toString()));}
            catch(Exception e){socket.close(CloseStatus.POLICY_VIOLATION);}return;
        }
        Client client=clients.get(socket);
        if(client==null){socket.close(CloseStatus.POLICY_VIOLATION);return;}
        client.lastSeen().set(System.currentTimeMillis());
        if(type.equals("PING")){try{long refreshed=sessions.require("Bearer "+node.path("token").asText());if(refreshed!=client.user())throw new IllegalStateException("Session owner changed");
            send(socket,Map.of("type","PONG","at",Instant.now().toString()));}catch(Exception e){clients.remove(socket);socket.close(CloseStatus.POLICY_VIOLATION);}return;}
        if(type.equals("SUBSCRIBE")){
            long id=node.path("vehicleId").asLong(-1);
            try{vehicles.owned(client.user(),id);client.vehicles().add(id);send(socket,Map.of("type","SUBSCRIBED","vehicleId",id,"at",Instant.now().toString()));}
            catch(Exception e){send(socket,Map.of("type","ERROR","code","VEHICLE_ACCESS_DENIED"));}return;
        }
        if(type.equals("UNSUBSCRIBE")){client.vehicles().remove(node.path("vehicleId").asLong());return;}
        send(socket,Map.of("type","ERROR","code","MESSAGE_TYPE"));
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){clients.remove(session);pending.remove(session);}
    @Override public void handleTransportError(WebSocketSession session,Throwable exception) throws Exception {clients.remove(session);pending.remove(session);session.close();}
    @Scheduled(fixedDelay=30000) public void closeSilentClients(){long cutoff=System.currentTimeMillis()-65000;
        for(var entry:clients.entrySet())if(entry.getValue().lastSeen().get()<cutoff){clients.remove(entry.getKey());try{entry.getKey().close(CloseStatus.SESSION_NOT_RELIABLE);}catch(Exception ignored){}}
        for(var entry:pending.entrySet())if(entry.getValue()<System.currentTimeMillis()-10000){pending.remove(entry.getKey());try{entry.getKey().close(CloseStatus.POLICY_VIOLATION);}catch(Exception ignored){}}
    }
    public void publish(long vehicleId,String type,Object payload){
        long now=System.currentTimeMillis();String key=vehicleId+":"+type;
        if((type.equals("STATUS_CHANGED")||type.equals("CHARGING_CHANGED"))&&now-lastSent.getOrDefault(key,0L)<1000)return;
        lastSent.put(key,now);
        Map<String,Object> event=Map.of("type",type,"vehicleId",vehicleId,"at",Instant.now().toString(),"payload",payload);
        for(var entry:clients.entrySet())if(entry.getValue().vehicles().contains(vehicleId)){
            try{vehicles.owned(entry.getValue().user(),vehicleId);send(entry.getKey(),event);}
            catch(Exception e){clients.remove(entry.getKey());try{entry.getKey().close(CloseStatus.POLICY_VIOLATION);}catch(Exception ignored){}}
        }
    }
    private void send(WebSocketSession socket,Object value) throws Exception {synchronized(socket){if(socket.isOpen())socket.sendMessage(new TextMessage(json.writeValueAsString(value)));}}
}
