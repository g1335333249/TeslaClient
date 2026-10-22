package cn.voltmate.tesla;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TeslaConfig {
    public final String mode,region,clientId,clientSecret,redirectUri,publicDomain,proxyUrl;
    public TeslaConfig(@Value("${app.mode:mock}") String mode,@Value("${app.region:CN}") String region,
        @Value("${app.tesla.client-id:}") String clientId,@Value("${app.tesla.client-secret:}") String clientSecret,
        @Value("${app.tesla.redirect-uri:}") String redirectUri,@Value("${app.tesla.public-domain:}") String publicDomain,
        @Value("${app.tesla.proxy-url:}") String proxyUrl) {
        this.mode=mode;this.region=region;this.clientId=clientId;this.clientSecret=clientSecret;this.redirectUri=redirectUri;this.publicDomain=publicDomain;this.proxyUrl=proxyUrl;
        if(!mode.equals("mock")&&!mode.equals("real")) throw new IllegalStateException("TESLA_MODE must be mock or real");
        if(!java.util.Set.of("CN","NA","EU").contains(region)) throw new IllegalStateException("TESLA_REGION invalid");
        if(mode.equals("real") && (clientId.isBlank()||clientSecret.isBlank()||redirectUri.isBlank()||publicDomain.isBlank()||proxyUrl.isBlank()))
            throw new IllegalStateException("Real mode requires Tesla credentials, HTTPS callback, public domain and command proxy");
        if(mode.equals("real") && !URI.create(redirectUri).getScheme().equals("https")) throw new IllegalStateException("Tesla callback must use HTTPS");
    }
    public String authBase(){return region.equals("CN")?"https://auth.tesla.cn":"https://auth.tesla.com";}
    public String fleetBase(){return switch(region){case "CN"->"https://fleet-api.prd.cn.vn.cloud.tesla.cn";case "EU"->"https://fleet-api.prd.eu.vn.cloud.tesla.com";default->"https://fleet-api.prd.na.vn.cloud.tesla.com";};}
}
