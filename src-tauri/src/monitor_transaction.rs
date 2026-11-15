pub(crate) fn replace_after_start<Monitor, Failure>(
    current: &mut Option<Monitor>,
    start: impl FnOnce() -> Result<Monitor, Failure>,
    commit: impl FnOnce(),
) -> Result<(), Failure> {
    let candidate = start()?;
    commit();
    let previous = current.replace(candidate);
    drop(previous);
    Ok(())
}
