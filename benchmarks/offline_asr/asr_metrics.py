from __future__ import annotations

import re
import unicodedata
from collections.abc import Iterable
from typing import TypedDict


class ErrorDetails(TypedDict):
    metric: str
    edits: int
    reference_units: int
    hypothesis_units: int
    error_rate: float


def _replace_punctuation_with_space(text: str) -> str:
    return "".join(
        " " if unicodedata.category(char)[0] in {"P", "S"} else char
        for char in unicodedata.normalize("NFKC", text)
    )


def normalize_words(text: str) -> str:
    """Normalize English-oriented text for case/punctuation-insensitive WER."""
    return re.sub(r"\s+", " ", _replace_punctuation_with_space(text).casefold()).strip()


def normalize_characters(text: str) -> str:
    """Normalize text for compact character comparison or entity matching."""
    return re.sub(r"\s+", "", _replace_punctuation_with_space(text).casefold())


def _is_han(char: str) -> bool:
    codepoint = ord(char)
    return (
        0x3400 <= codepoint <= 0x4DBF
        or 0x4E00 <= codepoint <= 0x9FFF
        or 0xF900 <= codepoint <= 0xFAFF
        or 0x20000 <= codepoint <= 0x2FA1F
        or 0x30000 <= codepoint <= 0x323AF
    )


def normalize_mixed_units(text: str) -> list[str]:
    """Tokenize mixed speech as Han characters plus contiguous non-Han words.

    This is the conventional mixed error-rate (MER) unit choice for the corpus:
    each Han character is one unit while an English word such as ``Android`` or
    ``OpenTypeless`` remains one unit. Punctuation, symbols, case, and width are
    normalized in the same way as the monolingual metrics.
    """
    normalized = _replace_punctuation_with_space(text).casefold()
    units: list[str] = []
    word: list[str] = []

    def flush_word() -> None:
        if word:
            units.append("".join(word))
            word.clear()

    for char in normalized:
        if _is_han(char):
            flush_word()
            units.append(char)
        elif char.isspace():
            flush_word()
        else:
            word.append(char)
    flush_word()
    return units


def metric_units(text: str, language: str) -> tuple[str, list[str]]:
    if language == "en":
        return "wer", normalize_words(text).split()
    if language == "mixed":
        return "mer", normalize_mixed_units(text)
    return "cer", list(normalize_characters(text))


def levenshtein(reference: list[str], hypothesis: list[str]) -> int:
    if len(reference) < len(hypothesis):
        reference, hypothesis = hypothesis, reference
    previous = list(range(len(hypothesis) + 1))
    for ref_index, ref_item in enumerate(reference, start=1):
        current = [ref_index]
        for hyp_index, hyp_item in enumerate(hypothesis, start=1):
            current.append(
                min(
                    current[-1] + 1,
                    previous[hyp_index] + 1,
                    previous[hyp_index - 1] + (ref_item != hyp_item),
                )
            )
        previous = current
    return previous[-1]


def error_details(reference: str, hypothesis: str, language: str) -> ErrorDetails:
    metric, reference_units = metric_units(reference, language)
    _, hypothesis_units = metric_units(hypothesis, language)
    edits = levenshtein(reference_units, hypothesis_units)
    if not reference_units:
        rate = float(bool(hypothesis_units))
    else:
        rate = edits / len(reference_units)
    return {
        "metric": metric,
        "edits": edits,
        "reference_units": len(reference_units),
        "hypothesis_units": len(hypothesis_units),
        "error_rate": rate,
    }


def error_rate(reference: str, hypothesis: str, language: str) -> float:
    return error_details(reference, hypothesis, language)["error_rate"]


def _is_latin_or_digit(char: str) -> bool:
    return char.isdigit() or "LATIN" in unicodedata.name(char, "")


def count_entity_occurrences(text: str, entity: str) -> int:
    """Count non-overlapping entity mentions without Latin substring matches.

    Spaces, punctuation, width, and case remain insignificant, but ``API`` no
    longer matches ``capital``. Han text may directly adjoin a Latin entity, as
    it normally does in Chinese sentences.
    """
    normalized_text = unicodedata.normalize("NFKC", text).casefold()
    searchable_characters: list[str] = []
    source_positions: list[int] = []
    for position, char in enumerate(normalized_text):
        if char.isspace() or unicodedata.category(char)[0] in {"P", "S"}:
            continue
        searchable_characters.append(char)
        source_positions.append(position)
    haystack = "".join(searchable_characters)
    needle = normalize_characters(entity)
    if not needle:
        return 0

    count = 0
    offset = 0
    while True:
        start = haystack.find(needle, offset)
        if start < 0:
            break
        end = start + len(needle)
        directly_adjacent_left = (
            start > 0 and source_positions[start - 1] + 1 == source_positions[start]
        )
        directly_adjacent_right = (
            end < len(haystack) and source_positions[end - 1] + 1 == source_positions[end]
        )
        invalid_left_boundary = (
            start > 0
            and directly_adjacent_left
            and _is_latin_or_digit(needle[0])
            and _is_latin_or_digit(haystack[start - 1])
        )
        invalid_right_boundary = (
            end < len(haystack)
            and directly_adjacent_right
            and _is_latin_or_digit(needle[-1])
            and _is_latin_or_digit(haystack[end])
        )
        if invalid_left_boundary or invalid_right_boundary:
            offset = start + 1
            continue
        count += 1
        offset = end
    return count


def contains_entity(text: str, entity: str) -> bool:
    return count_entity_occurrences(text, entity) > 0


def apply_alias_corrections(
    text: str, canonical_entities: list[str], spoken_aliases: list[str]
) -> str:
    """Apply explicit pronunciation aliases, as the product correction layer would.

    This is intentionally narrower than fuzzy matching: only a declared alias may
    become its paired canonical spelling. Punctuation, whitespace, and case between
    alias tokens are ignored, but unrelated words are never rewritten.
    """
    if len(canonical_entities) != len(spoken_aliases):
        raise ValueError("canonical_entities and spoken_aliases must have equal length")
    corrected = unicodedata.normalize("NFKC", text)
    for canonical, alias in zip(canonical_entities, spoken_aliases):
        compact_alias = normalize_characters(alias)
        if not compact_alias:
            continue
        left_boundary = r"(?<![A-Za-z0-9])" if _is_latin_or_digit(compact_alias[0]) else ""
        right_boundary = r"(?![A-Za-z0-9])" if _is_latin_or_digit(compact_alias[-1]) else ""
        pattern = re.compile(
            left_boundary
            + r"[\W_]*".join(re.escape(char) for char in compact_alias)
            + right_boundary,
            re.IGNORECASE | re.UNICODE,
        )
        corrected = pattern.sub(canonical, corrected)
    return corrected


def model_hotword_phrase(phrase: str) -> str:
    """Preserve the bilingual model's required uppercase hotword spelling."""
    return phrase.upper()


def percentile(values: Iterable[float], percentile_value: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    position = (len(ordered) - 1) * percentile_value
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = position - lower
    return ordered[lower] * (1 - fraction) + ordered[upper] * fraction
