ALTER TABLE vehicle_telemetry_raw
    ADD COLUMN ingested_at DATETIME(6) NULL,
    ADD COLUMN processing_state VARCHAR(24) NOT NULL DEFAULT 'PROCESSED',
    ADD COLUMN timestamp_quality VARCHAR(24) NOT NULL DEFAULT 'RECEIVED_ONLY',
    ADD COLUMN processing_error VARCHAR(120) NULL;
UPDATE vehicle_telemetry_raw SET ingested_at=received_at WHERE ingested_at IS NULL;
ALTER TABLE vehicle_telemetry_raw MODIFY ingested_at DATETIME(6) NOT NULL;
CREATE TABLE vehicle_signal_latest (
    vehicle_id BIGINT NOT NULL,
    signal_name VARCHAR(64) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    raw_id BIGINT NOT NULL,
    PRIMARY KEY(vehicle_id,signal_name),
    FOREIGN KEY(vehicle_id) REFERENCES vehicles(id),
    FOREIGN KEY(raw_id) REFERENCES vehicle_telemetry_raw(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX ix_raw_processing ON vehicle_telemetry_raw(processing_state,received_at);
