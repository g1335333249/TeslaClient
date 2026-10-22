package cn.voltmate.auth;

import cn.voltmate.core.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AppSessions {
    private final JdbcTemplate db; private final Crypto crypto; private final Ids ids;
    public AppSessions(JdbcTemplate db,Crypto crypto,Ids ids) {this.db=db;this.crypto=crypto;this.ids=ids;}
    public Map<String,Object> create(long userId,String clientType) {
        if (!java.util.Set.of("IOS","ANDROID","WEB").contains(clientType)) throw new ApiException(HttpStatus.BAD_REQUEST,"CLIENT_TYPE","无效客户端类型");
        String token=crypto.randomUrlToken();
        db.update("INSERT INTO user_sessions(id,user_id,token_hash,client_type,expires_at,created_at) VALUES(?,?,?,?,?,?)",ids.next(),userId,crypto.hash(token),clientType,java.sql.Timestamp.from(Instant.now().plus(30,ChronoUnit.DAYS)),java.sql.Timestamp.from(Instant.now()));
        return Map.of("token",token,"expiresAt",Instant.now().plus(30,ChronoUnit.DAYS).toString(),"userId",userId);
    }
    public long require(String authorization) {
        if (authorization==null || !authorization.startsWith("Bearer ")) throw new ApiException(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","请登录");
        var rows=db.queryForList("SELECT user_id FROM user_sessions WHERE token_hash=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(6)",crypto.hash(authorization.substring(7)));
        if(rows.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED,"SESSION_EXPIRED","会话已过期");
        return ((Number)rows.getFirst().get("user_id")).longValue();
    }
    public String clientType(String authorization){
        require(authorization);
        return db.queryForObject("SELECT client_type FROM user_sessions WHERE token_hash=?",String.class,crypto.hash(authorization.substring(7)));
    }
    public void revoke(String authorization) { require(authorization);db.update("UPDATE user_sessions SET revoked_at=UTC_TIMESTAMP(6) WHERE token_hash=?",crypto.hash(authorization.substring(7))); }
}
