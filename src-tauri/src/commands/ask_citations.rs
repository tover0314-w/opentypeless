use serde::Serialize;

#[derive(Clone, Debug, Serialize)]
pub(crate) struct AskCitation {
    pub url: String,
    pub title: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct AskCitationSupport {
    pub start: usize,
    pub end: usize,
    pub source_index: usize,
}

pub(crate) fn parse_citation_supports(
    answer: &str,
    value: &serde_json::Value,
    raw_sources: &serde_json::Value,
    sources: &[AskCitation],
) -> Vec<AskCitationSupport> {
    let (Some(entries), Some(raw_sources)) = (value.as_array(), raw_sources.as_array()) else {
        return Vec::new();
    };
    let ascii_end = answer.bytes().take_while(u8::is_ascii).count();
    let mut supports = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for entry in entries.iter().take(100) {
        let (Some(start), Some(end), Some(raw_index)) = (
            entry["start"]
                .as_u64()
                .and_then(|value| usize::try_from(value).ok()),
            entry["end"]
                .as_u64()
                .and_then(|value| usize::try_from(value).ok()),
            entry["sourceIndex"]
                .as_u64()
                .and_then(|value| usize::try_from(value).ok()),
        ) else {
            continue;
        };
        if end <= start || end > ascii_end || end - start > 4096 || raw_index >= 20 {
            continue;
        }
        let Some(raw_url) = raw_sources
            .get(raw_index)
            .and_then(|source| source["url"].as_str())
        else {
            continue;
        };
        let Ok(url) = url::Url::parse(raw_url) else {
            continue;
        };
        let Some(source_index) = sources.iter().position(|source| source.url == url.as_str())
        else {
            continue;
        };
        let segment = &answer[start..end];
        let candidate = if segment.starts_with('[') && segment.ends_with(')') {
            let Some((label, destination)) = segment[1..segment.len() - 1].split_once("](") else {
                continue;
            };
            if label.is_empty()
                || label.contains([']', '\r', '\n'])
                || destination.contains(['(', ')'])
            {
                continue;
            }
            destination
        } else {
            segment
        };
        if !candidate.starts_with("http://") && !candidate.starts_with("https://") {
            continue;
        }
        if candidate.chars().any(char::is_whitespace) {
            continue;
        }
        let Ok(candidate_url) = url::Url::parse(candidate) else {
            continue;
        };
        if candidate_url != url || !seen.insert((start, end, source_index)) {
            continue;
        }
        supports.push(AskCitationSupport {
            start,
            end,
            source_index,
        });
    }
    supports
}

pub(crate) fn parse_citations(value: &serde_json::Value) -> Vec<AskCitation> {
    let Some(entries) = value.as_array() else {
        return Vec::new();
    };
    let mut citations = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for entry in entries.iter().take(100) {
        let (Some(raw_url), Some(title)) = (entry["url"].as_str(), entry["title"].as_str()) else {
            continue;
        };
        if raw_url.len() > 2048
            || title.trim().is_empty()
            || title.encode_utf16().count() > 500
            || raw_url
                .chars()
                .any(|character| character <= ' ' || character == '\u{7f}' || character == '\\')
        {
            continue;
        }
        let Ok(url) = url::Url::parse(raw_url) else {
            continue;
        };
        if !matches!(url.scheme(), "http" | "https")
            || !raw_url
                .to_ascii_lowercase()
                .starts_with(&format!("{}://", url.scheme()))
            || url.host_str().is_none()
            || !url.username().is_empty()
            || url.password().is_some()
            || url.as_str().len() > 2048
        {
            continue;
        }
        if seen.insert(url.to_string()) {
            citations.push(AskCitation {
                url: url.to_string(),
                title: title.trim().to_string(),
            });
        }
        if citations.len() == 20 {
            break;
        }
    }
    citations
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn markdown_supports_are_deduplicated_and_annotation_inspection_is_bounded() {
        let url = "https://example.invalid/source";
        let link = format!("[Source]({url})");
        let raw_sources = json!([{"url":url,"title":"Source"}]);
        let sources = parse_citations(&raw_sources);
        let support = json!({"start":0,"end":link.len(),"sourceIndex":0});
        let parsed = parse_citation_supports(
            &link,
            &json!([support.clone(), support.clone()]),
            &raw_sources,
            &sources,
        );
        assert_eq!(parsed.len(), 1);
        let mut oversized = vec![serde_json::Value::Null; 100];
        oversized.push(support);
        assert!(
            parse_citation_supports(&link, &json!(oversized), &raw_sources, &sources).is_empty()
        );
    }

    #[test]
    fn verified_supports_preserve_answer_offsets_and_remap_filtered_source_indices() {
        let url = "https://example.invalid/source";
        let answer = format!("Read {url}");
        let raw_sources = json!([
            {"url":"javascript:alert(1)", "title":"Unsafe"},
            {"url":url, "title":"Source"}
        ]);
        let sources = parse_citations(&raw_sources);
        let supports = parse_citation_supports(
            &answer,
            &json!([{"start":5, "end":answer.len(), "sourceIndex":1, "content":"Private extra"}]),
            &raw_sources,
            &sources,
        );
        assert_eq!(
            serde_json::to_value(supports).unwrap(),
            json!([{"start":5, "end":answer.len(), "sourceIndex":0}])
        );
    }

    #[test]
    fn supports_never_guess_unicode_units_invalid_ranges_or_changed_answers() {
        let url = "https://example.invalid/source";
        let raw_sources = json!([{"url":url, "title":"Source"}]);
        let sources = parse_citations(&raw_sources);
        for (answer, start, end, source_index) in [
            (
                format!("😀 {url}"),
                json!(3),
                json!(3 + url.len()),
                json!(0),
            ),
            (format!("Read {url}"), json!(-1), json!(url.len()), json!(0)),
            (
                format!("Read {url}"),
                json!(5),
                json!(4 + url.len()),
                json!(0),
            ),
            (
                format!("Read {url}"),
                json!("5"),
                json!(5 + url.len()),
                json!(0),
            ),
            (
                format!("Read {url}"),
                json!(5),
                json!(5 + url.len()),
                json!(99),
            ),
            (
                format!("Changed Read {url}"),
                json!(5),
                json!(5 + url.len()),
                json!(0),
            ),
        ] {
            assert!(parse_citation_supports(
                &answer,
                &json!([{"start":start,"end":end,"sourceIndex":source_index}]),
                &raw_sources,
                &sources,
            )
            .is_empty());
        }
    }

    #[test]
    fn normalized_source_urls_stay_within_the_persisted_byte_limit() {
        let raw_url = format!("https://example.invalid/{}", "字".repeat(300));
        assert!(raw_url.len() < 2048);
        assert!(url::Url::parse(&raw_url).unwrap().as_str().len() > 2048);
        assert!(parse_citations(&json!([{"url":raw_url,"title":"Source"}])).is_empty());
        let short_url = format!("https://example.invalid/{}", "字".repeat(100));
        let parsed = parse_citations(&json!([{"url":short_url,"title":"Source"}]));
        assert_eq!(parsed.len(), 1);
        assert!(parsed[0].url.len() <= 2048);
    }

    #[test]
    fn source_title_limits_match_backup_utf16_limits() {
        assert!(parse_citations(&json!([
            {"url":"https://example.invalid","title":"x".repeat(501)},
            {"url":"https://example.invalid/emoji","title":"😀".repeat(251)}
        ]))
        .is_empty());
        assert_eq!(
            parse_citations(&json!([
                {"url":"https://example.invalid","title":"😀".repeat(250)}
            ]))
            .len(),
            1
        );
    }

    #[test]
    fn native_sources_are_bounded_deduplicated_and_do_not_trust_extra_fields() {
        let sources = parse_citations(&json!([
            {"url":"https://example.invalid/source", "title":"Source", "content":"private"},
            {"url":"https://example.invalid/source", "title":"Duplicate"},
            {"url":"javascript:alert(1)", "title":"Unsafe"},
            {"url":"https://user:secret@example.invalid", "title":"Unsafe"},
            {"url":"https://example.invalid\\@other.invalid", "title":"Unsafe"}
        ]));
        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].title, "Source");
        assert!(serde_json::to_value(&sources).unwrap()[0]
            .get("content")
            .is_none());
        let entries: Vec<_> = (0..150)
            .map(|index| json!({"url":format!("https://example.invalid/{index}"),"title":"Source"}))
            .collect();
        assert_eq!(parse_citations(&json!(entries)).len(), 20);
        assert!(parse_citations(&serde_json::Value::Null).is_empty());
    }
}
