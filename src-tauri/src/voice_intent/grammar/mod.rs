mod en;
mod zh_hans;
mod zh_hant;

use super::normalize::NormalizedUtterance;
use super::{CommandLocale, SearchProvider};

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum CommandMatch<T> {
    NoMatch,
    MissingPayload,
    Matched(T),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct SearchMatch {
    pub provider: SearchProvider,
    pub query: String,
}

pub(crate) fn match_draft(
    locale: CommandLocale,
    view: &NormalizedUtterance<'_>,
) -> CommandMatch<String> {
    match locale {
        CommandLocale::En => en::match_draft(view),
        CommandLocale::ZhHans => zh_hans::match_draft(view),
        CommandLocale::ZhHant => zh_hant::match_draft(view),
    }
}

pub(crate) fn matches_rewrite(locale: CommandLocale, view: &NormalizedUtterance<'_>) -> bool {
    match locale {
        CommandLocale::En => en::matches_rewrite(view),
        CommandLocale::ZhHans => zh_hans::matches_rewrite(view),
        CommandLocale::ZhHant => zh_hant::matches_rewrite(view),
    }
}

pub(crate) fn matches_translation(locale: CommandLocale, view: &NormalizedUtterance<'_>) -> bool {
    match locale {
        CommandLocale::En => en::matches_translation(view),
        CommandLocale::ZhHans => zh_hans::matches_translation(view),
        CommandLocale::ZhHant => zh_hant::matches_translation(view),
    }
}

pub(crate) fn translation_target(
    locale: CommandLocale,
    view: &NormalizedUtterance<'_>,
) -> Option<&'static str> {
    let prefixes: &[&str] = match locale {
        CommandLocale::En => &[
            "translate this to",
            "translate this into",
            "translate the selection to",
            "translate the selection into",
        ],
        CommandLocale::ZhHans => &["把这段翻译成", "翻译这段到", "将选中文字翻译成"],
        CommandLocale::ZhHant => &["把這段翻譯成", "翻譯這段到", "將選取文字翻譯成"],
    };
    let target = prefixes
        .iter()
        .find_map(|prefix| view.payload_after_prefix(prefix))?
        .to_lowercase();
    match target
        .trim()
        .trim_end_matches(['.', '。', '!', '！', '?', '？'])
        .trim()
    {
        "english" | "英语" | "英文" | "英語" => Some("en"),
        "chinese" | "simplified chinese" | "中文" | "汉语" | "漢語" => Some("zh"),
        "japanese" | "日语" | "日文" | "日語" => Some("ja"),
        "korean" | "韩语" | "韩文" | "韓語" | "韓文" => Some("ko"),
        "french" | "法语" | "法文" | "法語" => Some("fr"),
        "german" | "德语" | "德文" | "德語" => Some("de"),
        "spanish" | "西班牙语" | "西班牙語" => Some("es"),
        "portuguese" | "葡萄牙语" | "葡萄牙語" => Some("pt"),
        "russian" | "俄语" | "俄文" | "俄語" => Some("ru"),
        "arabic" | "阿拉伯语" | "阿拉伯語" => Some("ar"),
        "hindi" | "印地语" | "印地語" => Some("hi"),
        "thai" | "泰语" | "泰語" => Some("th"),
        "vietnamese" | "越南语" | "越南語" => Some("vi"),
        "italian" | "意大利语" | "義大利語" | "意大利語" => Some("it"),
        "dutch" | "荷兰语" | "荷蘭語" => Some("nl"),
        "turkish" | "土耳其语" | "土耳其語" => Some("tr"),
        "polish" | "波兰语" | "波蘭語" => Some("pl"),
        "ukrainian" | "乌克兰语" | "烏克蘭語" => Some("uk"),
        "indonesian" | "印度尼西亚语" | "印尼语" | "印尼語" => Some("id"),
        "malay" | "马来语" | "馬來語" => Some("ms"),
        _ => None,
    }
}

pub(crate) fn matches_informational(locale: CommandLocale, view: &NormalizedUtterance<'_>) -> bool {
    match locale {
        CommandLocale::En => en::matches_informational(view),
        CommandLocale::ZhHans => zh_hans::matches_informational(view),
        CommandLocale::ZhHant => zh_hant::matches_informational(view),
    }
}

pub(crate) fn match_search(
    locale: CommandLocale,
    view: &NormalizedUtterance<'_>,
) -> CommandMatch<SearchMatch> {
    match locale {
        CommandLocale::En => en::match_search(view),
        CommandLocale::ZhHans => zh_hans::match_search(view),
        CommandLocale::ZhHant => zh_hant::match_search(view),
    }
}

pub(crate) fn exact_confidence(view: &NormalizedUtterance<'_>) -> f32 {
    if view
        .match_text()
        .chars()
        .last()
        .is_some_and(|character| matches!(character, '.' | '。' | '!' | '！' | '?' | '？'))
    {
        0.9
    } else {
        1.0
    }
}
