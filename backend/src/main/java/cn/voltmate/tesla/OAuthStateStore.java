package cn.voltmate.tesla;

import cn.voltmate.core.ApiException;
import cn.voltmate.core.Crypto;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthStateStore {
    private final JdbcTemplate db;
    private final Crypto crypto;
    public OAuthStateStore(JdbcTemplate db,Crypto crypto){this.db=db;this.crypto=crypto;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Map<String,Object> consume(String state){
        if(state==null||state.isBlank())throw new ApiException(HttpStatus.BAD_REQUEST,"OAUTH_STATE","授权状态无效");
        String hash=crypto.hash(state);
        var rows=db.queryForList("SELECT user_id,client_type,nonce_hash FROM oauth_states WHERE state_hash=? AND consumed_at IS NULL AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE",hash);
        if(rows.isEmpty())throw new ApiException(HttpStatus.BAD_REQUEST,"OAUTH_STATE","授权状态无效或已使用");
        db.update("UPDATE oauth_states SET consumed_at=UTC_TIMESTAMP(6) WHERE state_hash=?",hash);
        return rows.getFirst();
    }
}
