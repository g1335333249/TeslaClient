CREATE TABLE vehicle_capability_snapshots (
    vehicle_id BIGINT PRIMARY KEY,
    model VARCHAR(50),
    firmware_version VARCHAR(80),
    telemetry_version VARCHAR(80),
    command_protocol_required BOOLEAN,
    key_state VARCHAR(20) NOT NULL,
    scopes VARCHAR(512) NOT NULL,
    capabilities_json JSON NOT NULL,
    checked_at DATETIME(6) NOT NULL,
    FOREIGN KEY(vehicle_id) REFERENCES vehicles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
