// src/power.rs

/// Power states for the mesh engine, controlling radio duty cycles.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum PowerState {
    Emergency = 0,
    Active = 1,
    Balanced = 2,
    PowerSaver = 3,
    Minimal = 4,
    Hibernation = 5,
    Dead = 6,
}

impl PowerState {
    /// Convert from u8 (used for JNI).
    pub fn from_u8(value: u8) -> Option<Self> {
        match value {
            0 => Some(PowerState::Emergency),
            1 => Some(PowerState::Active),
            2 => Some(PowerState::Balanced),
            3 => Some(PowerState::PowerSaver),
            4 => Some(PowerState::Minimal),
            5 => Some(PowerState::Hibernation),
            6 => Some(PowerState::Dead),
            _ => None,
        }
    }

    /// Convert to u8 (used for JNI).
    pub fn as_u8(self) -> u8 {
        self as u8
    }
}

/// Compute the power state based on battery level, charging status, node density,
/// and an optional user override.
///
/// # Arguments
/// * `battery` - Battery percentage (0-100).
/// * `charging` - Whether the device is currently charging.
/// * `density` - Node density factor (not currently used, reserved for future).
/// * `user_override` - Optional user-selected power state override.
pub fn compute_state(
    battery: u8,
    charging: bool,
    _density: f32,
    user_override: Option<PowerState>,
) -> PowerState {
    if let Some(state) = user_override {
        return state;
    }

    if charging {
        return PowerState::Active;
    }

    match battery {
        0..=5 => PowerState::Dead,
        6..=15 => PowerState::Hibernation,
        16..=30 => PowerState::Minimal,
        31..=50 => PowerState::PowerSaver,
        51..=80 => PowerState::Balanced,
        _ => PowerState::Active,
    }
}

/// Get the BLE scanning parameters (interval_ms, window_ms) for a given power state.
/// Returns (0, 0) for states where scanning should be disabled.
pub fn get_scan_params(state: PowerState) -> (u32, u32) {
    match state {
        PowerState::Emergency => (1000, 500),
        PowerState::Active => (1000, 250),
        PowerState::Balanced => (5000, 250),
        PowerState::PowerSaver => (30000, 100),
        PowerState::Minimal => (120000, 50),
        PowerState::Hibernation => (0, 0),
        PowerState::Dead => (0, 0),
    }
}

/// Get the OGM broadcast interval in seconds for a given power state.
pub fn get_ogm_interval_secs(state: PowerState) -> u64 {
    match state {
        PowerState::Emergency => 1,
        PowerState::Active => 5,
        PowerState::Balanced => 10,
        PowerState::PowerSaver => 30,
        PowerState::Minimal => 60,
        PowerState::Hibernation => 300,
        PowerState::Dead => 0,
    }
}

/// Check if BLE advertising should be enabled for a given power state.
pub fn should_advertise(state: PowerState) -> bool {
    match state {
        PowerState::Dead | PowerState::Hibernation => false,
        _ => true,
    }
}

/// Check if WiFi Direct should be enabled for a given power state.
pub fn should_enable_wifi(state: PowerState) -> bool {
    match state {
        PowerState::Emergency | PowerState::Active | PowerState::Balanced => true,
        PowerState::PowerSaver | PowerState::Minimal | PowerState::Hibernation | PowerState::Dead => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_compute_state_by_battery() {
        assert_eq!(compute_state(0, false, 0.0, None), PowerState::Dead);
        assert_eq!(compute_state(3, false, 0.0, None), PowerState::Dead);
        assert_eq!(compute_state(5, false, 0.0, None), PowerState::Dead);
        assert_eq!(compute_state(6, false, 0.0, None), PowerState::Hibernation);
        assert_eq!(compute_state(15, false, 0.0, None), PowerState::Hibernation);
        assert_eq!(compute_state(16, false, 0.0, None), PowerState::Minimal);
        assert_eq!(compute_state(30, false, 0.0, None), PowerState::Minimal);
        assert_eq!(compute_state(31, false, 0.0, None), PowerState::PowerSaver);
        assert_eq!(compute_state(50, false, 0.0, None), PowerState::PowerSaver);
        assert_eq!(compute_state(51, false, 0.0, None), PowerState::Balanced);
        assert_eq!(compute_state(80, false, 0.0, None), PowerState::Balanced);
        assert_eq!(compute_state(81, false, 0.0, None), PowerState::Active);
        assert_eq!(compute_state(100, false, 0.0, None), PowerState::Active);
    }

    #[test]
    fn test_compute_state_charging() {
        // Charging should always yield Active regardless of battery level
        assert_eq!(compute_state(0, true, 0.0, None), PowerState::Active);
        assert_eq!(compute_state(10, true, 0.0, None), PowerState::Active);
        assert_eq!(compute_state(50, true, 0.0, None), PowerState::Active);
        assert_eq!(compute_state(100, true, 0.0, None), PowerState::Active);
    }

    #[test]
    fn test_compute_state_user_override() {
        // User override should take precedence over battery and charging
        assert_eq!(
            compute_state(100, false, 0.0, Some(PowerState::PowerSaver)),
            PowerState::PowerSaver
        );
        assert_eq!(
            compute_state(10, true, 0.0, Some(PowerState::Minimal)),
            PowerState::Minimal
        );
        assert_eq!(
            compute_state(0, false, 0.0, Some(PowerState::Active)),
            PowerState::Active
        );
    }

    #[test]
    fn test_get_scan_params() {
        assert_eq!(get_scan_params(PowerState::Emergency), (1000, 500));
        assert_eq!(get_scan_params(PowerState::Active), (1000, 250));
        assert_eq!(get_scan_params(PowerState::Balanced), (5000, 250));
        assert_eq!(get_scan_params(PowerState::PowerSaver), (30000, 100));
        assert_eq!(get_scan_params(PowerState::Minimal), (120000, 50));
        assert_eq!(get_scan_params(PowerState::Hibernation), (0, 0));
        assert_eq!(get_scan_params(PowerState::Dead), (0, 0));
    }

    #[test]
    fn test_get_ogm_interval_secs() {
        assert_eq!(get_ogm_interval_secs(PowerState::Emergency), 1);
        assert_eq!(get_ogm_interval_secs(PowerState::Active), 5);
        assert_eq!(get_ogm_interval_secs(PowerState::Balanced), 10);
        assert_eq!(get_ogm_interval_secs(PowerState::PowerSaver), 30);
        assert_eq!(get_ogm_interval_secs(PowerState::Minimal), 60);
        assert_eq!(get_ogm_interval_secs(PowerState::Hibernation), 300);
        assert_eq!(get_ogm_interval_secs(PowerState::Dead), 0);
    }

    #[test]
    fn test_should_advertise() {
        assert!(should_advertise(PowerState::Emergency));
        assert!(should_advertise(PowerState::Active));
        assert!(should_advertise(PowerState::Balanced));
        assert!(should_advertise(PowerState::PowerSaver));
        assert!(should_advertise(PowerState::Minimal));
        assert!(!should_advertise(PowerState::Hibernation));
        assert!(!should_advertise(PowerState::Dead));
    }

    #[test]
    fn test_should_enable_wifi() {
        assert!(should_enable_wifi(PowerState::Emergency));
        assert!(should_enable_wifi(PowerState::Active));
        assert!(should_enable_wifi(PowerState::Balanced));
        assert!(!should_enable_wifi(PowerState::PowerSaver));
        assert!(!should_enable_wifi(PowerState::Minimal));
        assert!(!should_enable_wifi(PowerState::Hibernation));
        assert!(!should_enable_wifi(PowerState::Dead));
    }
      }
