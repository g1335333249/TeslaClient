ALTER TABLE driving_trips ADD COLUMN start_odometer_km DECIMAL(12,3), ADD COLUMN last_motion_at DATETIME(6);
ALTER TABLE charging_sessions ADD COLUMN last_power_at DATETIME(6);
