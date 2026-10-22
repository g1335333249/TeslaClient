package cn.voltmate.telemetry;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.integration.StaticMessageHeaderAccessor;

@Configuration
@ConditionalOnProperty(name="app.mqtt.enabled",havingValue="true")
public class MqttConfig {
    @Bean
    DefaultMqttPahoClientFactory mqttFactory(@Value("${app.mqtt.url}") String url,@Value("${app.mqtt.username:}") String user,@Value("${app.mqtt.password:}") String password){
        var options=new MqttConnectOptions();options.setServerURIs(new String[]{url});options.setAutomaticReconnect(true);options.setCleanSession(false);
        if(!user.isBlank()){options.setUserName(user);options.setPassword(password.toCharArray());}
        var factory=new DefaultMqttPahoClientFactory();factory.setConnectionOptions(options);return factory;
    }
    @Bean MessageChannel telemetryInput(){return new DirectChannel();}
    @Bean MqttPahoMessageDrivenChannelAdapter mqttInbound(DefaultMqttPahoClientFactory factory){
        var adapter=new MqttPahoMessageDrivenChannelAdapter("voltmate-backend",factory,"telemetry/+/v/+","telemetry/+/connectivity","telemetry/+/errors/+");
        adapter.setQos(1);adapter.setManualAcks(true);adapter.setOutputChannel(telemetryInput());return adapter;
    }
    @Bean
    @ServiceActivator(inputChannel="telemetryInput")
    MessageHandler telemetryHandler(TelemetryIngestor ingestor){
        return message->{
            ingestor.ingest((String)message.getHeaders().get("mqtt_receivedTopic"),String.valueOf(message.getPayload()));
            var acknowledgment=StaticMessageHeaderAccessor.getAcknowledgment(message);
            if(acknowledgment!=null)acknowledgment.acknowledge();
        };
    }
}
