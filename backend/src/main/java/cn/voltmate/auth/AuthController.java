package cn.voltmate.auth;

import cn.voltmate.core.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AppSessions sessions;private final JdbcTemplate db;private final Ids ids;private final String mode;
    public AuthController(AppSessions sessions,JdbcTemplate db,Ids ids,@Value("${app.mode:mock}") String mode){this.sessions=sessions;this.db=db;this.ids=ids;this.mode=mode;}
    @PostMapping("/demo")
    public Map<String,Object> demo(@RequestBody(required=false) Map<String,String> body){
        if(!"mock".equals(mode)) throw new ApiException(HttpStatus.NOT_FOUND,"MOCK_DISABLED","演示模式未启用");
        long userId=1001L;
        db.update("INSERT IGNORE INTO users(id,display_name,created_at) VALUES(?,?,?)",userId,"演示用户",java.sql.Timestamp.from(Instant.now()));
        return sessions.create(userId,body==null?"IOS":body.getOrDefault("clientType","IOS"));
    }
    @PostMapping("/logout") public Map<String,String> logout(@RequestHeader("Authorization") String authorization){sessions.revoke(authorization);return Map.of("status","revoked");}
    @PostMapping("/refresh") public Map<String,Object> refresh(@RequestHeader("Authorization") String authorization){
        long user=sessions.require(authorization);String clientType=sessions.clientType(authorization);sessions.revoke(authorization);return sessions.create(user,clientType);
    }
}
