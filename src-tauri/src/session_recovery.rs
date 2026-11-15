use std::path::PathBuf;

pub struct SessionRecoveryGuard {
    directory: PathBuf,
}

impl SessionRecoveryGuard {
    pub fn new(directory: PathBuf) -> Self {
        Self { directory }
    }

    pub fn is_blocked(&self) -> Result<bool, String> {
        match std::fs::symlink_metadata(self.directory.join("session-recovery-blocked")) {
            Ok(_) => Ok(true),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(false),
            Err(_) => Err("Cloud session recovery status unavailable".into()),
        }
    }

    pub fn block(&self) -> Result<(), String> {
        std::fs::create_dir_all(&self.directory)
            .map_err(|_| "Cloud session recovery protection unavailable".to_string())?;
        let marker = self.directory.join("session-recovery-blocked");
        let file = match std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&marker)
        {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                let metadata = std::fs::symlink_metadata(&marker)
                    .map_err(|_| "Cloud session recovery protection unavailable".to_string())?;
                if !metadata.file_type().is_file() {
                    return Err("Cloud session recovery protection unavailable".into());
                }
                std::fs::OpenOptions::new()
                    .write(true)
                    .open(&marker)
                    .map_err(|_| "Cloud session recovery protection unavailable".to_string())?
            }
            Err(_) => return Err("Cloud session recovery protection unavailable".into()),
        };
        file.sync_all()
            .map_err(|_| "Cloud session recovery protection could not be saved".to_string())?;
        self.sync_directory()
    }

    pub fn allow(&self) -> Result<(), String> {
        match std::fs::remove_file(self.directory.join("session-recovery-blocked")) {
            Ok(()) => self.sync_directory(),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(_) => Err("Cloud session recovery protection could not be cleared".into()),
        }
    }

    fn sync_directory(&self) -> Result<(), String> {
        #[cfg(unix)]
        {
            std::fs::File::open(&self.directory)
                .and_then(|directory| directory.sync_all())
                .map_err(|_| "Cloud session recovery directory could not be synced".to_string())?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn directory_at_marker_path_does_not_count_as_saved_protection() {
        let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        std::fs::create_dir_all(directory.join("session-recovery-blocked")).unwrap();
        let guard = SessionRecoveryGuard::new(directory.clone());
        assert!(guard.is_blocked().unwrap());
        assert!(guard.block().is_err());
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn recovery_block_survives_reopening_and_is_removed_only_explicitly() {
        let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let guard = SessionRecoveryGuard::new(directory.clone());
        assert!(!guard.is_blocked().unwrap());
        guard.block().unwrap();
        let reopened = SessionRecoveryGuard::new(directory.clone());
        assert!(reopened.is_blocked().unwrap());
        reopened.allow().unwrap();
        assert!(!guard.is_blocked().unwrap());
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn invalid_marker_is_blocking_not_an_authorization_to_restore() {
        let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        std::fs::create_dir_all(&directory).unwrap();
        std::fs::write(directory.join("session-recovery-blocked"), b"").unwrap();
        let guard = SessionRecoveryGuard::new(directory.clone());
        assert!(guard.is_blocked().unwrap());
        std::fs::remove_dir_all(directory).unwrap();
    }
}
