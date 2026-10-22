package cn.voltmate.telemetry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionJob {
    private final JdbcTemplate db;
    public RetentionJob(JdbcTemplate db){this.db=db;}
    @Scheduled(fixedDelay=60000)
    public void markStale(){
        db.update("UPDATE vehicle_status_latest SET online_state='UNKNOWN' WHERE source='TELEMETRY' AND received_at<UTC_TIMESTAMP(6)-INTERVAL 15 MINUTE AND online_state='ONLINE'");
    }
    @Scheduled(cron="0 20 3 * * *",zone="UTC")
    public void clean(){
        db.update("DELETE r FROM vehicle_telemetry_raw r JOIN vehicle_user_access a ON a.vehicle_id=r.vehicle_id JOIN user_settings s ON s.user_id=a.user_id LEFT JOIN vehicle_signal_latest l ON l.raw_id=r.id WHERE l.raw_id IS NULL AND r.received_at < UTC_TIMESTAMP(6)-INTERVAL s.retention_days DAY");
        db.update("DELETE p FROM vehicle_telemetry_samples p JOIN vehicle_user_access a ON a.vehicle_id=p.vehicle_id JOIN user_settings s ON s.user_id=a.user_id WHERE p.received_at < UTC_TIMESTAMP(6)-INTERVAL s.retention_days DAY");
    }
}
