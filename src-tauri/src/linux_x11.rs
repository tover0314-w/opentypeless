#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub enum XInitThreadsStatus {
    Enabled,
    Unavailable,
    Failed,
}

pub fn init_xlib_threads() -> XInitThreadsStatus {
    init_xlib_threads_with(|| unsafe { xinitthreads_from_libx11() })
}

fn init_xlib_threads_with(
    load: impl FnOnce() -> Result<unsafe extern "C" fn() -> i32, libloading::Error>,
) -> XInitThreadsStatus {
    let Ok(xinitthreads) = load() else {
        return XInitThreadsStatus::Unavailable;
    };

    if unsafe { xinitthreads() } == 0 {
        XInitThreadsStatus::Failed
    } else {
        XInitThreadsStatus::Enabled
    }
}

unsafe fn xinitthreads_from_libx11() -> Result<unsafe extern "C" fn() -> i32, libloading::Error> {
    // Leak the handle intentionally: the process may later use Xlib via GTK/WebKit.
    // Keeping libX11 loaded avoids invalidating the resolved function or Xlib state.
    let lib = Box::leak(Box::new(unsafe { libloading::Library::new("libX11.so.6")? }));
    let symbol = unsafe { lib.get::<unsafe extern "C" fn() -> i32>(b"XInitThreads\0")? };
    Ok(*symbol)
}

#[cfg(test)]
mod tests {
    use super::*;

    unsafe extern "C" fn xinitthreads_success() -> i32 {
        1
    }

    unsafe extern "C" fn xinitthreads_failure() -> i32 {
        0
    }

    #[test]
    fn reports_enabled_when_xinitthreads_succeeds() {
        let status = init_xlib_threads_with(|| Ok(xinitthreads_success));

        assert_eq!(status, XInitThreadsStatus::Enabled);
    }

    #[test]
    fn reports_failed_when_xinitthreads_returns_zero() {
        let status = init_xlib_threads_with(|| Ok(xinitthreads_failure));

        assert_eq!(status, XInitThreadsStatus::Failed);
    }

    #[test]
    fn reports_unavailable_when_libx11_cannot_be_loaded() {
        let status = init_xlib_threads_with(|| {
            unsafe { libloading::Library::new("__opentypeless_missing_lib__.so") }
                .map(|_| xinitthreads_success as unsafe extern "C" fn() -> i32)
        });

        assert_eq!(status, XInitThreadsStatus::Unavailable);
    }
}
