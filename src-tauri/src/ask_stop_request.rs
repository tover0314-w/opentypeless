#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum AskStopDisposition {
    Pending,
    Recording(u64),
    Rejected,
}

pub(crate) fn resolve_stop_request(
    expected_generation: u64,
    current_generation: u64,
    starting: bool,
    recording_session_id: Option<u64>,
    processing: bool,
) -> AskStopDisposition {
    if expected_generation != current_generation || processing {
        return AskStopDisposition::Rejected;
    }
    if starting {
        return AskStopDisposition::Pending;
    }
    recording_session_id
        .map(AskStopDisposition::Recording)
        .unwrap_or(AskStopDisposition::Rejected)
}
