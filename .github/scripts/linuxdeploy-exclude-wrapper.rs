use std::env;
use std::ffi::OsString;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{self, Command};

const WAYLAND_CLIENT_GLOB: &str = "libwayland-client.so*";

fn real_linuxdeploy_path(wrapper_path: &Path) -> Result<PathBuf, String> {
    let wrapper_name = wrapper_path
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or_else(|| "linuxdeploy wrapper path is not valid UTF-8".to_string())?;
    let architecture = wrapper_name
        .strip_prefix("linuxdeploy-")
        .and_then(|name| name.strip_suffix(".AppImage"))
        .ok_or_else(|| format!("unexpected linuxdeploy wrapper name: {wrapper_name}"))?;

    Ok(wrapper_path.with_file_name(format!("linuxdeploy-real-{architecture}.AppImage")))
}

fn forwarded_arguments(arguments: impl IntoIterator<Item = OsString>) -> Vec<OsString> {
    let mut forwarded: Vec<OsString> = arguments.into_iter().collect();
    forwarded.push(OsString::from("--exclude-library"));
    forwarded.push(OsString::from(WAYLAND_CLIENT_GLOB));
    forwarded
}

fn main() {
    let wrapper_path = env::current_exe().unwrap_or_else(|error| {
        eprintln!("failed to resolve linuxdeploy wrapper path: {error}");
        process::exit(127);
    });
    let real_linuxdeploy = real_linuxdeploy_path(&wrapper_path).unwrap_or_else(|error| {
        eprintln!("{error}");
        process::exit(127);
    });
    let arguments = forwarded_arguments(env::args_os().skip(1));

    let error = Command::new(&real_linuxdeploy).args(arguments).exec();
    eprintln!(
        "failed to execute the real linuxdeploy binary at {}: {error}",
        real_linuxdeploy.display()
    );
    process::exit(127);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_the_real_binary_next_to_the_wrapper() {
        let wrapper = Path::new("/tmp/tauri/linuxdeploy-x86_64.AppImage");
        assert_eq!(
            real_linuxdeploy_path(wrapper).unwrap(),
            Path::new("/tmp/tauri/linuxdeploy-real-x86_64.AppImage")
        );
    }

    #[test]
    fn appends_the_wayland_client_exclusion_without_changing_existing_arguments() {
        let arguments = forwarded_arguments([
            OsString::from("--appdir"),
            OsString::from("OpenTypeless.AppDir"),
        ]);
        assert_eq!(
            arguments,
            [
                "--appdir",
                "OpenTypeless.AppDir",
                "--exclude-library",
                WAYLAND_CLIENT_GLOB,
            ]
            .map(OsString::from)
        );
    }
}
