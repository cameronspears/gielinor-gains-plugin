# Reading level

Everything a player reads should suit a 5th-6th grade reader: under grade 7.0 on the
Flesch-Kincaid scale, the same bar as gielinorgains.com.

`ReadingLevelTest` checks this on every build. It grades six groups: the box, its tooltip, the
Market list, the GE guide, the settings and this repo's README. It fails if any group reaches grade 7.0, or if any sentence runs
past 20 words. It also writes the text it graded to `build/copy/`.

The test counts syllables with a simple rule. To check it, the same text was also scored with the
site's own scorer (`scripts/copy/readabilityScore.ts` in the site repo). Both treat numbers, short
abbreviations and names as one syllable, and grade only sentences, not labels such as "Buy offer".

| Group       | This repo's test | Site scorer | Sentences |
| ----------- | ---------------- | ----------- | --------- |
| Box         | 2.9              | 3.4         | 22        |
| Tooltip     | 5.0              | 5.0         | 94        |
| Market list | 3.3              | 3.8         | 24        |
| GE guide    | 0.3              | —           | —         |
| Settings    | 2.7              | —           | —         |
| README      | 3.3              | —           | —         |

The GE guide grades so low because its lines are short commands ("Click Confirm.").

Measured 25 October 2026. The settings, GE guide and README rows were re-measured with this
repo's test on 3 November 2026, after the v2 README; the site scorer wasn't re-run for them.

The box's reasons for not suggesting an item, and its fallback sentence, are the site's own, word for
word: `GoldenStringsTest` checks them against the site's `golden-strings.json`.
