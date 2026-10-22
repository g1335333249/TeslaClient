package cn.voltmate.tesla;

import cn.voltmate.core.Crypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class TeslaTokensTest {
    @Test void expiredTokenIsRefreshedAndRotated() throws Exception {
        JdbcTemplate db=mock(JdbcTemplate.class);Crypto crypto=mock(Crypto.class);TeslaHttp http=mock(TeslaHttp.class);
        var config=new TeslaConfig("mock","CN","app-id","","","","");
        Map<String,Object> row=new HashMap<>();row.put("authorization_state","ACTIVE");row.put("access_ciphertext","old-access");row.put("refresh_ciphertext","old-refresh");row.put("expires_at",Timestamp.from(Instant.now().minusSeconds(1)));
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row));
        when(crypto.decrypt("old-refresh")).thenReturn("refresh-secret");
        when(crypto.encrypt("new-access")).thenReturn("encrypted-access");
        when(crypto.encrypt("new-refresh")).thenReturn("encrypted-refresh");
        when(http.form(eq("https://auth.tesla.cn/oauth2/v3/token"),anyMap())).thenReturn(new ObjectMapper().readTree("{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600}"));
        assertThat(new TeslaTokens(db,crypto,config,http).access(10)).isEqualTo("new-access");
        verify(crypto).encrypt("new-refresh");
        verify(db).update(contains("UPDATE tesla_tokens SET"),eq("encrypted-access"),eq("encrypted-refresh"),any(),any(),eq(10L));
    }
}
