use cpal::traits::{DeviceTrait, HostTrait};

/// An input device the user can pick for dictation.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InputDeviceInfo {
    pub name: String,
    pub is_default: bool,
    /// `builtIn`, `usb`, `bluetooth`, `virtual` or `other`.
    pub connection: &'static str,
}

fn connection_kind(interface: cpal::InterfaceType) -> &'static str {
    match interface {
        cpal::InterfaceType::BuiltIn => "builtIn",
        cpal::InterfaceType::Usb => "usb",
        cpal::InterfaceType::Bluetooth => "bluetooth",
        cpal::InterfaceType::Virtual | cpal::InterfaceType::Aggregate => "virtual",
        _ => "other",
    }
}

fn device_name(device: &cpal::Device) -> Option<String> {
    device
        .description()
        .ok()
        .map(|description| description.name().to_string())
}

/// Lists available input devices with the system default first.
pub fn list_input_devices() -> Vec<InputDeviceInfo> {
    let host = cpal::default_host();
    let default_name = host.default_input_device().as_ref().and_then(device_name);
    let Ok(devices) = host.input_devices() else {
        return Vec::new();
    };

    let mut seen = std::collections::HashSet::new();
    let mut listed: Vec<InputDeviceInfo> = devices
        .filter_map(|device| {
            let description = device.description().ok()?;
            let name = description.name().to_string();
            if name.trim().is_empty() || !seen.insert(name.clone()) {
                return None;
            }
            Some(InputDeviceInfo {
                is_default: default_name.as_deref() == Some(name.as_str()),
                connection: connection_kind(description.interface_type()),
                name,
            })
        })
        .collect();
    listed.sort_by_key(|device| !device.is_default);
    listed
}

/// Picks the configured input device, falling back to the system default when it is unset or
/// no longer connected. Returns the device and whether a fallback happened.
pub(crate) fn select_input_device(preferred: Option<&str>) -> Option<(cpal::Device, bool)> {
    let host = cpal::default_host();
    let preferred = preferred.map(str::trim).filter(|name| !name.is_empty());
    if let Some(preferred) = preferred {
        let matched = host.input_devices().ok().and_then(|mut devices| {
            devices.find(|device| device_name(device).as_deref() == Some(preferred))
        });
        if let Some(device) = matched {
            return Some((device, false));
        }
        tracing::warn!("Preferred microphone '{preferred}' is unavailable; using the default");
    }
    host.default_input_device()
        .map(|device| (device, preferred.is_some()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connection_kind_groups_interfaces_for_the_picker() {
        assert_eq!(connection_kind(cpal::InterfaceType::BuiltIn), "builtIn");
        assert_eq!(connection_kind(cpal::InterfaceType::Usb), "usb");
        assert_eq!(connection_kind(cpal::InterfaceType::Bluetooth), "bluetooth");
        assert_eq!(connection_kind(cpal::InterfaceType::Aggregate), "virtual");
        assert_eq!(connection_kind(cpal::InterfaceType::Hdmi), "other");
    }

    #[test]
    fn input_device_info_serializes_for_the_frontend() {
        let value = serde_json::to_value(InputDeviceInfo {
            name: "MacBook Pro Microphone".to_string(),
            is_default: true,
            connection: "builtIn",
        })
        .unwrap();
        assert_eq!(
            value,
            serde_json::json!({
                "name": "MacBook Pro Microphone",
                "isDefault": true,
                "connection": "builtIn"
            })
        );
    }
}
