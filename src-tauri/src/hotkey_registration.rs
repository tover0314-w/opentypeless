#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct AdapterRegistrationReport {
    pub reset: Result<(), String>,
    pub global: Vec<Result<(), String>>,
    pub native: Result<(), String>,
}

impl AdapterRegistrationReport {
    pub fn error(&self) -> Option<String> {
        let mut errors = Vec::new();
        if let Err(error) = &self.reset {
            errors.push(error.clone());
        }
        for error in self
            .global
            .iter()
            .filter_map(|result| result.as_ref().err())
        {
            if !errors.contains(error) {
                errors.push(error.clone());
            }
        }
        if let Err(error) = &self.native {
            if !errors.contains(error) {
                errors.push(error.clone());
            }
        }
        (!errors.is_empty()).then(|| errors.join("; "))
    }
}

pub(crate) fn register_adapters<Binding>(
    bindings: &[Binding],
    reset: impl FnOnce() -> Result<(), String>,
    mut register: impl FnMut(&Binding) -> Result<(), String>,
    native: impl FnOnce() -> Result<(), String>,
) -> AdapterRegistrationReport {
    let reset = reset();
    let global = bindings
        .iter()
        .map(|binding| match &reset {
            Ok(()) => register(binding),
            Err(error) => Err(error.clone()),
        })
        .collect();
    let native = native();
    AdapterRegistrationReport {
        reset,
        global,
        native,
    }
}
