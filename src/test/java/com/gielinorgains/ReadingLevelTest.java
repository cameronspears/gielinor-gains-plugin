package com.gielinorgains;

import static com.gielinorgains.Fixtures.MINUTE;
import static com.gielinorgains.Fixtures.SOURCE_AT;
import static com.gielinorgains.Fixtures.STALE_AT;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Flesch-Kincaid grade of every word a player reads: the box, its tooltip, the settings and the
 * README. The target is a 5th-6th grade reader, so each must stay under grade 7.0, the site's
 * own bar (scripts/copy/check-readability.ts).
 *
 * As on the site, numbers, short abbreviations (GE, GP) and names count as one syllable, and only
 * text that reads as a sentence is graded, not labels such as "Buy offer". Syllables are counted
 * with a simple vowel-group rule here; docs/reading-level.md records the same copy scored by the
 * site's own scorer, from the files this test writes to build/copy/.
 */
public class ReadingLevelTest
{
	private static final double MAX_GRADE = 7.0;

	/** Names counted as one syllable a word, as the site's scorer does. */
	private static final List<String> NAMES = Arrays.asList(
		"Gielinor Gains", "Grand Exchange", "RuneLite", "OSRS Wiki", "Plugin Hub", "Market", "Chance");

	@Test
	public void everyGroupReadsAtAFifthToSixthGradeLevel() throws Exception
	{
		Map<String, List<String>> groups = groups();
		Path out = Paths.get("build", "copy");
		Files.createDirectories(out);
		StringBuilder report = new StringBuilder();
		for (Map.Entry<String, List<String>> group : groups.entrySet())
		{
			Files.write(out.resolve(group.getKey() + ".txt"), group.getValue(), StandardCharsets.UTF_8);
			double grade = grade(String.join(" ", group.getValue()));
			report.append(String.format(Locale.ROOT, "%-9s grade %.1f%n", group.getKey(), grade));
			assertTrue(group.getKey() + " reads at grade " + grade, grade < MAX_GRADE);
		}
		Files.write(out.resolve("grades.txt"), report.toString().getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void noSingleSentenceIsAMouthful() throws Exception
	{
		for (List<String> group : groups().values())
		{
			for (String text : group)
			{
				for (String sentence : sentences(text))
				{
					assertTrue("too long: " + sentence, words(sentence).size() <= 20);
				}
			}
		}
	}

	/** The words players read, grouped by where they read them. Only sentences are graded. */
	static Map<String, List<String>> groups() throws Exception
	{
		Set<String> box = new LinkedHashSet<>();
		Set<String> tooltip = new LinkedHashSet<>();
		ItemCheck.Response outdated = Fixtures.itemCheck();
		outdated.minPlugin = ItemCheck.PLUGIN_VERSION + 1;
		List<ItemCheck.Snapshot> snapshots = Arrays.asList(
			Fixtures.snapshot(), Fixtures.edgeSnapshot(), null, ItemCheck.Snapshot.of(outdated, 0, 0));
		for (ItemCheck.Snapshot snapshot : snapshots)
		{
			for (int id : new int[]{2, 561, 995, 4151, 11840, 20997, 13190, 565, 999_999})
			{
				for (long now : new long[]{SOURCE_AT + 6 * MINUTE, STALE_AT + 7 * MINUTE, SOURCE_AT + 90 * MINUTE})
				{
					for (boolean flag : new boolean[]{false, true})
					{
						BoxText.Box b = BoxText.forItem(snapshot, flag, id, flag, now);
						for (BoxText.Line line : b.getLines())
						{
							if (line.getRight() == null && isSentence(line.getLeft()))
							{
								box.add(line.getLeft());
							}
						}
						tooltip.add(String.join(" ", b.getTooltip()));
					}
				}
			}
		}
		box.addAll(BoxText.REASONS.values());

		List<String> settings = Arrays.asList(GielinorGainsConfig.MARKET_DESCRIPTION, GielinorGainsConfig.GUIDE_DESCRIPTION,
			GielinorGainsConfig.COLOR_DESCRIPTION, GielinorGainsConfig.NOTIFY_DESCRIPTION, GielinorGainsConfig.SOUND_DESCRIPTION,
			GielinorGainsConfig.BUYING_DESCRIPTION, GielinorGainsConfig.SELLING_DESCRIPTION,
			GielinorGainsConfig.INFOBOX_DESCRIPTION, GielinorGainsConfig.MOTION_DESCRIPTION, GielinorGainsConfig.GEAR_DESCRIPTION);

		Set<String> panel = new LinkedHashSet<>(Arrays.asList(MarketList.MEMBERS_HIDDEN, MarketList.NO_FLIPS,
			MarketList.NO_FREE_FLIPS, MarketList.LOG_IN, MarketList.OTHER_WORLD, GearPanel.WHAT, GearPanel.LOG_IN, GearPanel.OFF,
			GearPanel.FAILED, GearPanel.NONE, GearPanel.NO_BANK, GearPanel.PRIVACY));
		for (MarketList.Row row : MarketListTest.view(Fixtures.snapshot(), false, SOURCE_AT + 6 * MINUTE, true).getRows())
		{
			panel.add(String.join(" ", row.getTooltip()));
		}

		Map<String, List<String>> groups = new LinkedHashMap<>();
		groups.put("box", new ArrayList<>(box));
		groups.put("tooltip", new ArrayList<>(tooltip));
		groups.put("panel", new ArrayList<>(panel));
		// The guide's lines are short commands ("Click Confirm."), so they're graded together, sentence by sentence.
		List<String> guide = new ArrayList<>();
		for (String words : GuideTest.allWords())
		{
			guide.add(words.replace(" / ", " ").replaceAll("([^.!?])$", "$1."));
		}
		guide.add(ItemCheckOverlay.GUIDE_TIP);
		guide.add(MarketPanel.GUIDE_HINT);
		groups.put("guide", guide);
		groups.put("settings", settings);
		groups.put("readme", readme());
		return groups;
	}

	/** README prose: paragraphs and list items, without headings, tables, code or comments. */
	private static List<String> readme() throws Exception
	{
		String text = new String(Files.readAllBytes(Paths.get("README.md")), StandardCharsets.UTF_8)
			.replaceAll("(?s)<!--.*?-->", "")
			.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
			.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
			.replace("**", "");
		List<String> out = new ArrayList<>();
		for (String line : text.split("\n"))
		{
			String t = line.trim().replaceFirst("^(- |\\d+\\. )", "");
			if (!t.isEmpty() && !t.startsWith("#") && !t.startsWith("|") && isSentence(t))
			{
				out.add(t);
			}
		}
		return out;
	}

	/** As the site's isSentenceLike: 8+ words, or 4+ ending like a sentence. */
	private static boolean isSentence(String text)
	{
		int n = words(text).size();
		return n >= 8 || (n >= 4 && text.trim().matches(".*[.!?:]$"));
	}

	static double grade(String text)
	{
		List<String> sentences = sentences(text);
		int words = 0;
		int syllables = 0;
		for (String sentence : sentences)
		{
			String marked = sentence;
			for (String name : NAMES)
			{
				marked = marked.replace(name, name.replaceAll("[^ ]+", "Name"));
			}
			List<String> w = words(marked);
			words += w.size();
			for (int i = 0; i < w.size(); i++)
			{
				syllables += syllables(w.get(i), i == 0);
			}
		}
		return 0.39 * words / sentences.size() + 11.8 * syllables / words - 15.59;
	}

	private static List<String> sentences(String text)
	{
		List<String> out = new ArrayList<>();
		for (String s : text.trim().split("(?<=[.!?])\\s+"))
		{
			if (!words(s).isEmpty())
			{
				out.add(s);
			}
		}
		return out;
	}

	private static final Pattern WORD = Pattern.compile("[A-Za-z0-9][A-Za-z0-9'%,.+-]*[A-Za-z0-9%]|[A-Za-z0-9]");

	private static List<String> words(String text)
	{
		List<String> out = new ArrayList<>();
		Matcher m = WORD.matcher(text);
		while (m.find())
		{
			out.add(m.group());
		}
		return out;
	}

	private static int syllables(String word, boolean first)
	{
		String bare = word.replaceAll("'s$", "");
		if (bare.matches(".*\\d.*") || bare.matches("[A-Z]{2,4}") || bare.equals("Name")
			|| (!first && bare.matches("[A-Z].*") && !bare.equals("I")))
		{
			return 1;
		}
		String w = bare.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
		if (w.length() <= 3)
		{
			return 1;
		}
		w = w.replaceAll("(?:[^laeiouy]es|ed|[^laeiouy]e)$", "").replaceAll("^y", "");
		int count = 0;
		Matcher m = Pattern.compile("[aeiouy]{1,2}").matcher(w);
		while (m.find())
		{
			count++;
		}
		return Math.max(1, count);
	}
}
