package cn.voltmate.tesla;

import cn.voltmate.core.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TeslaHttp {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper json;
    public TeslaHttp(ObjectMapper json){this.json=json;}
    public JsonNode form(String url,Map<String,String> fields){
        String body=fields.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }
    public JsonNode get(String url,String token){return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token).GET().build());}
    public JsonNode post(String url,String token,String body){return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());}
    public JsonNode delete(String url,String token){return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token).DELETE().build());}
    private JsonNode send(HttpRequest request) {
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==401) throw new ApiException(HttpStatus.UNAUTHORIZED,"TESLA_AUTH_EXPIRED","Tesla 授权已失效，请重新授权");
            if(response.statusCode()==429) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"TESLA_RATE_LIMIT","Tesla 请求过于频繁");
            if(response.statusCode()<200||response.statusCode()>=300) throw new ApiException(HttpStatus.BAD_GATEWAY,"TESLA_API_ERROR","Tesla 服务返回 "+response.statusCode());
            return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
        } catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(HttpStatus.BAD_GATEWAY,"TESLA_UNAVAILABLE","Tesla 服务暂不可用");}
    }
    public static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
}
