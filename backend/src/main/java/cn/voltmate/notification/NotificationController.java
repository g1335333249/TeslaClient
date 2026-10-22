package cn.voltmate.notification;

import cn.voltmate.auth.AppSessions;
import cn.voltmate.core.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    public record Notification(long id,Long vehicleId,String type,String title,String body,Instant createdAt,Instant readAt){}
    private final AppSessions sessions;private final JdbcTemplate db;
    public NotificationController(AppSessions sessions,JdbcTemplate db){this.sessions=sessions;this.db=db;}
    @GetMapping
    public List<Notification> list(@RequestHeader("Authorization") String auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        long user=sessions.require(auth);
        if(page<0||size<1||size>100)throw new ApiException(HttpStatus.BAD_REQUEST,"PAGE","分页参数无效");
        return db.query("SELECT * FROM notification_records WHERE user_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (r,n)->new Notification(r.getLong("id"),(Long)r.getObject("vehicle_id"),r.getString("type"),r.getString("title"),r.getString("body"),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("read_at")==null?null:r.getTimestamp("read_at").toInstant()),
            user,size,page*size);
    }
    @PostMapping("/{id}/read")
    public Map<String,String> read(@RequestHeader("Authorization") String auth,@PathVariable long id){
        int n=db.update("UPDATE notification_records SET read_at=COALESCE(read_at,UTC_TIMESTAMP(6)) WHERE id=? AND user_id=?",id,sessions.require(auth));
        if(n==0)throw new ApiException(HttpStatus.NOT_FOUND,"NOTIFICATION_NOT_FOUND","通知不存在");
        return Map.of("status","read");
    }
}
