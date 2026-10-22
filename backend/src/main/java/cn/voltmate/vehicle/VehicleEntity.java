package cn.voltmate.vehicle;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="vehicles")
public class VehicleEntity {
    @Id private Long id;
    private String vin;
    @Column(name="display_name") private String displayName;
    private String model;
    private String color;
    private String firmware;
    private String region;
    @Column(name="key_state") private String keyState;
    @Column(name="account_id") private Long accountId;
    @Column(name="created_at") private Instant createdAt;
    protected VehicleEntity(){}
    public Long getId(){return id;}
    public String getVin(){return vin;}
    public String getDisplayName(){return displayName;}
    public String getModel(){return model;}
    public String getColor(){return color;}
    public String getFirmware(){return firmware;}
    public String getRegion(){return region;}
    public String getKeyState(){return keyState;}
}
