CREATE TABLE charging_plans (
    id BIGINT PRIMARY KEY,
    vehicle_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    tesla_schedule_id BIGINT NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    start_minute INT,
    end_minute INT,
    start_enabled BOOLEAN NOT NULL,
    end_enabled BOOLEAN NOT NULL,
    days_of_week VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL,
    one_time BOOLEAN NOT NULL,
    apply_state VARCHAR(24) NOT NULL,
    last_error VARCHAR(120),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_plan_vehicle_tesla(vehicle_id,tesla_schedule_id),
    KEY ix_plan_user_vehicle(user_id,vehicle_id),
    FOREIGN KEY(vehicle_id) REFERENCES vehicles(id),
    FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE charging_tariffs
    ADD COLUMN day_mask INT NOT NULL DEFAULT 127,
    ADD COLUMN valid_from DATE NULL,
    ADD COLUMN valid_to DATE NULL,
    ADD COLUMN priority INT NOT NULL DEFAULT 0;
CREATE TABLE charging_energy_samples (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    grid_kwh_total DECIMAL(12,4),
    battery_kwh_total DECIMAL(12,4),
    source VARCHAR(24) NOT NULL,
    UNIQUE KEY uk_energy_session_time_source(session_id,observed_at,source),
    KEY ix_energy_session_time(session_id,observed_at),
    FOREIGN KEY(session_id) REFERENCES charging_sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE charging_session_costs (
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    amount DECIMAL(12,2),
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    accuracy VARCHAR(24) NOT NULL,
    source VARCHAR(24) NOT NULL,
    manual_amount DECIMAL(12,2),
    calculated_at DATETIME(6) NOT NULL,
    PRIMARY KEY(session_id,user_id),
    KEY ix_cost_user_time(user_id,calculated_at),
    FOREIGN KEY(session_id) REFERENCES charging_sessions(id),
    FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
