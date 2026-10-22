package cn.voltmate.tesla;

import cn.voltmate.auth.AppSessions;
import cn.voltmate.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/tesla")
public class TeslaOAuthController {
    private final TeslaConfig config;private final TeslaHttp http;private final JdbcTemplate db;private final Crypto crypto;private final Ids ids;private final AppSessions sessions;private final OAuthStateStore states;
    public TeslaOAuthController(TeslaConfig config,TeslaHttp http,JdbcTemplate db,Crypto crypto,Ids ids,AppSessions sessions,OAuthStateStore states){this.config=config;this.http=http;this.db=db;this.crypto=crypto;this.ids=ids;this.sessions=sessions;this.states=states;}
    @PostMapping("/start")
    public Map<String,String> start(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody(required=false) Map<String,String> body){
        if(!config.mode.equals("real")) throw new ApiException(HttpStatus.BAD_REQUEST,"REAL_MODE_REQUIRED","请配置真实 Tesla 模式");
        Long user=authorization==null?null:sessions.require(authorization);
        String state=crypto.randomUrlToken(),nonce=crypto.randomUrlToken();
        String clientType=body==null?"IOS":body.getOrDefault("clientType","IOS");
        if(!Set.of("IOS","ANDROID","WEB").contains(clientType))throw new ApiException(HttpStatus.BAD_REQUEST,"CLIENT_TYPE","无效客户端类型");
        db.update("INSERT INTO oauth_states(state_hash,user_id,nonce_hash,client_type,expires_at,created_at) VALUES(?,?,?,?,?,?)",
            crypto.hash(state),user,crypto.hash(nonce),clientType,Timestamp.from(Instant.now().plusSeconds(600)),Timestamp.from(Instant.now()));
        String scopes="openid offline_access vehicle_device_data vehicle_location vehicle_cmds vehicle_charging_cmds";
        String url=config.authBase()+"/oauth2/v3/authorize?response_type=code&client_id="+TeslaHttp.encode(config.clientId)+
            "&redirect_uri="+TeslaHttp.encode(config.redirectUri)+"&scope="+TeslaHttp.encode(scopes)+"&state="+TeslaHttp.encode(state)+"&nonce="+TeslaHttp.encode(nonce);
        return Map.of("authorizationUrl",url,"state",state);
    }
    @GetMapping("/callback")
    @Transactional
    public ResponseEntity<?> callback(@RequestParam String state,@RequestParam(required=false) String code,@RequestParam(required=false) String error){
        var pending=states.consume(state);
        if(error!=null) throw new ApiException(HttpStatus.BAD_REQUEST,"OAUTH_DENIED","Tesla 授权未完成");
        if(code==null||code.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,"OAUTH_CODE","缺少授权码");
        JsonNode token=http.form(config.authBase()+"/oauth2/v3/token",Map.of("grant_type","authorization_code","client_id",config.clientId,
            "client_secret",config.clientSecret,"code",code,"audience",config.fleetBase(),"redirect_uri",config.redirectUri));
        String access=token.path("access_token").asText(),refresh=token.path("refresh_token").asText();
        if(access.isBlank()||refresh.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY,"TESLA_TOKEN","Tesla 未返回完整授权凭证");
        String idToken=token.path("id_token").asText();
        if(idToken.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY,"TESLA_ID_TOKEN","Tesla 未返回身份凭证");
        Jwt jwt;
        try {
            String jwks=config.authBase()+"/oauth2/v3/discovery/thirdparty/keys";
            var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(config.authBase()+"/oauth2/v3/nts"));
            jwt=decoder.decode(idToken);
            if(!jwt.getAudience().contains(config.clientId)||!crypto.hash(jwt.getClaimAsString("nonce")).equals(pending.get("nonce_hash")))
                throw new IllegalArgumentException("Invalid audience or nonce");
        }catch(Exception ex){throw new ApiException(HttpStatus.BAD_GATEWAY,"TESLA_ID_TOKEN_INVALID","Tesla 身份凭证校验失败");}
        String subject=jwt.getSubject();
        var existing=db.queryForList("SELECT id,user_id FROM tesla_accounts WHERE region=? AND tesla_subject=?",config.region,subject);
        Long pendingUser=pending.get("user_id")==null?null:((Number)pending.get("user_id")).longValue();
        long user,accountId;
        if(!existing.isEmpty()){
            user=((Number)existing.getFirst().get("user_id")).longValue();
            if(pendingUser!=null&&pendingUser!=user)throw new ApiException(HttpStatus.CONFLICT,"ACCOUNT_LINKED","该 Tesla 账户已绑定其他应用用户");
            accountId=((Number)existing.getFirst().get("id")).longValue();
        }else{
            user=pendingUser==null?ids.next():pendingUser;
            if(pendingUser==null)db.update("INSERT INTO users(id,display_name,created_at) VALUES(?,'Tesla 用户',UTC_TIMESTAMP(6))",user);
            accountId=ids.next();
        }
        String scope=token.path("scope").asText("");
        if(existing.isEmpty())db.update("INSERT INTO tesla_accounts(id,user_id,region,tesla_subject,scopes,authorization_state,created_at) VALUES(?,?,?,?,?,'ACTIVE',?)",
            accountId,user,config.region,subject,scope,Timestamp.from(Instant.now()));
        else db.update("UPDATE tesla_accounts SET scopes=?,authorization_state='ACTIVE' WHERE id=?",scope,accountId);
        db.update("INSERT INTO tesla_tokens(account_id,access_ciphertext,refresh_ciphertext,expires_at,updated_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE access_ciphertext=VALUES(access_ciphertext),refresh_ciphertext=VALUES(refresh_ciphertext),expires_at=VALUES(expires_at),updated_at=VALUES(updated_at)",
            accountId,crypto.encrypt(access),crypto.encrypt(refresh),Timestamp.from(Instant.now().plusSeconds(token.path("expires_in").asLong(3600))),Timestamp.from(Instant.now()));
        String ticket=crypto.randomUrlToken();
        db.update("INSERT INTO oauth_login_tickets(ticket_hash,user_id,client_type,expires_at,created_at) VALUES(?,?,?,?,?)",
            crypto.hash(ticket),user,pending.get("client_type"),Timestamp.from(Instant.now().plusSeconds(120)),Timestamp.from(Instant.now()));
        String clientType=(String)pending.get("client_type");
        String callback=switch(clientType){
            case "ANDROID" -> "voltmate-android://oauth?ticket="+TeslaHttp.encode(ticket);
            case "WEB" -> "https://"+config.publicDomain+"/oauth/complete?ticket="+TeslaHttp.encode(ticket);
            default -> "voltmate://oauth?ticket="+TeslaHttp.encode(ticket);
        };
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(callback)).build();
    }
    @PostMapping("/complete")
    @Transactional
    public Map<String,Object> complete(@RequestBody Map<String,String> body){
        String ticket=body.getOrDefault("ticket","");
        var rows=db.queryForList("SELECT user_id,client_type FROM oauth_login_tickets WHERE ticket_hash=? AND consumed_at IS NULL AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE",crypto.hash(ticket));
        if(rows.isEmpty())throw new ApiException(HttpStatus.BAD_REQUEST,"OAUTH_TICKET","授权回执已失效");
        db.update("UPDATE oauth_login_tickets SET consumed_at=UTC_TIMESTAMP(6) WHERE ticket_hash=?",crypto.hash(ticket));
        return sessions.create(((Number)rows.getFirst().get("user_id")).longValue(),(String)rows.getFirst().get("client_type"));
    }
    public record Account(long id,String region,String scopes,String authorizationState){}
    @GetMapping("/consent-management")
    public Map<String,String> consentManagement(@RequestHeader("Authorization") String authorization){
        sessions.require(authorization);
        if(config.mode.equals("mock"))return Map.of("url","");
        return Map.of("url",config.authBase()+"/user/revoke/consent?revoke_client_id="+TeslaHttp.encode(config.clientId)+"&back_url="+TeslaHttp.encode("https://"+config.publicDomain));
    }
    @GetMapping("/accounts")
    public List<Account> accounts(@RequestHeader("Authorization") String authorization){
        long user=sessions.require(authorization);
        return db.query("SELECT id,region,scopes,authorization_state FROM tesla_accounts WHERE user_id=? ORDER BY created_at DESC",
            (r,n)->new Account(r.getLong("id"),r.getString("region"),r.getString("scopes"),r.getString("authorization_state")),user);
    }
    @DeleteMapping("/accounts/{accountId}")
    @Transactional
    public Map<String,String> unlink(@RequestHeader("Authorization") String authorization,@PathVariable long accountId){
        long user=sessions.require(authorization);
        int count=db.update("UPDATE tesla_accounts SET authorization_state='REVOKED' WHERE id=? AND user_id=?",accountId,user);
        if(count==0) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","授权账户不存在");
        db.update("DELETE a FROM vehicle_user_access a JOIN vehicles v ON v.id=a.vehicle_id WHERE v.account_id=? AND a.user_id=?",accountId,user);
        db.update("DELETE FROM tesla_tokens WHERE account_id=?",accountId);
        return Map.of("status","revoked");
    }
}
