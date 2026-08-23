# OpenTypeless Emoji inventory provenance

The source-level inventory in `EmojiCatalog.java` is a manually curated subset of the fully-qualified sequences listed by
Unicode Emoji 15.1. It contains 168 sequences across smileys, people, animals, food, activities, travel, objects and symbols.
No Unicode chart image, font, annotation text or runtime data parser is included.

- Source: https://www.unicode.org/Public/emoji/15.1/emoji-test.txt
- Version: Unicode Emoji 15.1
- License: Unicode License v3 (`Unicode-3.0`), retained in `LICENSE.txt`
- Purpose: bounded offline Emoji picker for `KBD-010`
- Update strategy: explicit reviewed catalog/version change with duplicate, bound, UI and selected-IME tests
