package cn.voltmate.tesla;

import cn.voltmate.core.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeslaTokens {
    private final JdbcTemplate db;private final Crypto crypto;private final TeslaConfig config;private final TeslaHttp http;
    public TeslaTokens(JdbcTemplate db,Crypto crypto,TeslaConfig config,TeslaHttp http){this.db=db;this.crypto=crypto;this.config=config;this.http=http;}
    @Transactional(noRollbackFor=ApiException.class)
    public String access(long accountId) {
        var rows=db.queryForList("SELECT t.access_ciphertext,t.refresh_ciphertext,t.expires_at,a.authorization_state FROM tesla_tokens t JOIN tesla_accounts a ON a.id=t.account_id WHERE t.account_id=? FOR UPDATE",accountId);
        if(rows.isEmpty()||!"ACTIVE".equals(rows.getFirst().get("authorization_state"))) throw new ApiException(HttpStatus.UNAUTHORIZED,"TESLA_AUTH_EXPIRED","请重新授权 Tesla");
        var row=rows.getFirst();
        if(((Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now().plusSeconds(120))) return crypto.decrypt((String)row.get("access_ciphertext"));
        try {
            var refreshed=http.form(config.authBase()+"/oauth2/v3/token",Map.of("grant_type","refresh_token","client_id",config.clientId,"refresh_token",crypto.decrypt((String)row.get("refresh_ciphertext"))));
            String access=refreshed.path("access_token").asText(),refresh=refreshed.path("refresh_token").asText();
            if(access.isBlank()||refresh.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY,"TESLA_TOKEN","刷新凭证不完整");
            db.update("UPDATE tesla_tokens SET access_ciphertext=?,refresh_ciphertext=?,expires_at=?,updated_at=? WHERE account_id=?",
                crypto.encrypt(access),crypto.encrypt(refresh),Timestamp.from(Instant.now().plusSeconds(refreshed.path("expires_in").asLong(3600))),Timestamp.from(Instant.now()),accountId);
            return access;
        } catch(ApiException e) {
            if(e.status==HttpStatus.UNAUTHORIZED) db.update("UPDATE tesla_accounts SET authorization_state='EXPIRED' WHERE id=?",accountId);
            throw e;
        }
    }
}
