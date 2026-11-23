use crate::error::AppError;
use crate::output::{InsertResult, InsertionStrategy};

pub(crate) fn type_shifted_newline<Failure>(
    press: impl FnOnce() -> Result<(), Failure>,
    newline: impl FnOnce() -> Result<(), Failure>,
    release: impl FnOnce() -> Result<(), Failure>,
) -> Result<(), Failure> {
    press()?;
    let result = newline();
    let released = release();
    result.and(released)
}

pub(crate) fn finish_keyboard_attempt(
    result: Result<(), AppError>,
    completed_chars: usize,
) -> Result<InsertResult, AppError> {
    match result {
        Ok(()) => Ok(InsertResult::inserted(
            InsertionStrategy::Keyboard,
            completed_chars,
        )),
        Err(error) if completed_chars > 0 => {
            let mut partial =
                InsertResult::partially_inserted(InsertionStrategy::Keyboard, completed_chars);
            partial.message = Some(error.to_string());
            Ok(partial)
        }
        Err(error) => Err(error),
    }
}
