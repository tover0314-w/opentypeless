fn main() {
    #[cfg(target_os = "macos")]
    println!("cargo:rustc-link-lib=framework=Speech");

    #[cfg(target_os = "windows")]
    println!(
        "cargo:rustc-link-arg=/MANIFESTDEPENDENCY:type='win32' name='Microsoft.Windows.Common-Controls' version='6.0.0.0' processorArchitecture='*' publicKeyToken='6595b64144ccf1df' language='*'"
    );

    tauri_build::build()
}
