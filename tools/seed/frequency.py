#!/usr/bin/env python3
"""Builds app/src/main/assets/seed/spanish_mx_frequency.sctxt from the two frequencylists.blogspot.com posts.

Usage: python frequency.py TOP1000.html NOUNS2000.html
  TOP1000.html:   https://frequencylists.blogspot.com/2015/08/the-1000-most-frequently-used-spanish.html
  NOUNS2000.html: https://frequencylists.blogspot.com/2015/12/the-2000-most-frequently-used-spanish.html
Save each page (curl -L) and pass the files. Notes keep the lists' order and rank. A word that appears
twice on one list becomes one note at its first rank, with the other meanings added; a word on both
lists stays on both. frequency_extras.json (keyed by note id) holds each note's example sentence and links,
and optional corrections for Mexican Spanish: "spanish" (a Mexican word in place of a Spain one; the list's
word is kept in Notes), "gender_pos", and "english" (replaces the whole English field).
"""
import html, json, os, re, sys, unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
EXTRAS = os.path.join(HERE, "frequency_extras.json")
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "seed", "spanish_mx_frequency.sctxt")
PARENT = "Frecuencia"
DECK_1000 = f"{PARENT}::1 · 1000 palabras más usadas"
DECK_2000 = f"{PARENT}::2 · 2000 sustantivos más usados"

# Glosses garbled (or wrong) on the 1000-word page, by rank.
WORD_FIXES = {
    96: "you [obj, informal]",
    112: "nothing, [not] at all",
    216: "favor, grace, [pl] thank you",
    307: "you [subj, formal]",
    575: "your [informal, sg]",
    612: "you [subj, informal sg]",
    718: "far [away], [far] off",
}
# Noun glosses that don't fit the Spanish word, by rank.
NOUN_GLOSS_FIXES = {17: "feeling", 186: "feeling"}
# Obvious typos on the noun page.
NOUN_FIXES = {"alcalde/alcalde": "alcalde", "ganso/o": "ganso"}


def post_lines(path):
    src = open(path, encoding="utf-8", errors="replace").read()
    m = re.search(r"class='post-body[^>]*>(.*?)<div class='post-footer", src, re.S)
    body = m.group(1) if m else src
    body = re.sub(r"<br\s*/?>|</p>|</div>|</li>|</tr>", "\n", body, flags=re.I)
    body = html.unescape(re.sub(r"<[^>]+>", "", body))
    body = unicodedata.normalize("NFKC", body)
    return [l.strip() for l in body.splitlines() if l.strip()]


def tidy(s):
    s = s.replace("“", '"').replace("”", '"')
    s = re.sub(r"(?<=\w)\[", " [", s)      # "to be[norm]" -> "to be [norm]"
    s = re.sub(r"\]\s*(?=\w)", "] ", s)
    s = re.sub(r"\s+,", ",", s)
    return re.sub(r"\s+", " ", s).strip()


def top1000(path):
    rows = []
    for l in post_lines(path):
        m = re.match(r"^(\d+)\s*_\s*(.+?)\s+-\s+(.*)$", l)
        if m: rows.append((int(m[1]), tidy(m[2]), WORD_FIXES.get(int(m[1]), tidy(m[3]))))
    return rows


def gender_label(text):
    """'masculine/feminine', 'plural, feminine', 'feminine (plural)' ... -> 'm./f. noun', 'f. pl. noun'."""
    t = re.sub(r"\([^)]*\)", lambda m: " plural " if "plural" in m[0] else " ", text.lower())
    m, f = "masculine" in t, "feminine" in t
    g = "m./f." if m and f else "m." if m else "f." if f else ""
    if "plural" in t: g += " pl."
    return f"{g} noun".strip()


def gender_notes(text):
    return [p for p in re.findall(r"\(([^)]*)\)", text) if p.strip().lower() != "plural"]


def noun(rest):
    """'tiempo - masculine' / 'coche - masculine / auto - masculine' / 'nota (academic), grado (degree) - feminine, masculine'."""
    rest = re.sub(r"\s*\([^)]*$", "", rest)  # a bracket the page cut off
    # "word - gender / word - gender" (or with commas): several alternatives, each with its gender.
    pairs = None
    for sep in ("/", ","):
        pieces = re.split(rf"\s*{sep}\s*", rest)
        if len(pieces) > 1 and all(" - " in p for p in pieces):
            pairs = [p.rpartition(" - ")[::2] for p in pieces]
            break
    if pairs is None:
        spanish, _, g = rest.rpartition(" - ")
        pairs = [(spanish, g)]
    if len({w.strip() for w, _ in pairs}) == 1:  # "idiota - masculine / idiota - feminine"
        pairs = [(pairs[0][0], " ".join(g for _, g in pairs))]
    words = [w.strip() for w, _ in pairs]
    notes = [n for _, g in pairs for n in gender_notes(g)]
    labels = [gender_label(g).removesuffix(" noun") for _, g in pairs]
    label = (labels[0] if len(set(labels)) == 1 else " / ".join(labels)) + " noun"
    explained = [w for w in words if "(" in w]
    if explained:  # meanings in brackets go to Notes so the front (and its speech) is just Spanish
        notes.insert(0, ", ".join(words))
    spanish = " / ".join(re.sub(r"\s*\([^)]*\)", "", w) for w in words)
    spanish = NOUN_FIXES.get(spanish, spanish)
    return spanish, label, "; ".join(notes)


def nouns2000(path):
    rows = []
    for l in post_lines(path):
        m = re.match(r"^(\d+)\.\s*(.+?)\s+-\s+(.*)$", l)
        if m: rows.append((int(m[1]), NOUN_GLOSS_FIXES.get(int(m[1]), m[2].strip()), *noun(m[3].strip())))
    return rows


def merge_repeats(rows, spanish, english, rank=lambda r: r[0]):
    """Keeps each Spanish word once, at its first rank; later meanings are appended. Returns (row, other ranks)."""
    kept, by_word = [], {}
    for r in rows:
        key = spanish(r).lower()
        if key in by_word:
            first = by_word[key]
            first[2].append(rank(r))
            seen = {m.strip().lower() for m in english(first[0]).split(",")}
            extra = [m.strip() for m in english(r).split(",") if m.strip().lower() not in seen]
            if extra: first[1].extend(extra)
            continue
        entry = [r, [], []]
        by_word[key] = entry
        kept.append(entry)
    return kept


def ranks_note(rank, others, what):
    also = f" (also #{', #'.join(map(str, others))})" if others else ""
    return f"#{rank}{also} of the {what}"


def extra_fields(extras, nid, ids):
    e = extras.get(nid)
    if not e: return []
    out = [f"Example (ES): {e['es']}", f"Example (EN): {e['en']}"]
    links = {}
    for l in e.get("links", []):
        if l["target"] not in ids: continue  # merged into another note by a correction
        links.setdefault(l["type"], []).append(l["target"])
    if links:
        out.append("links: " + "; ".join(f"{t} " + " ".join(f"[[{x}]]" for x in ts) for t, ts in links.items()))
    return out


def main():
    a, b = sys.argv[1:3]
    words, nouns = top1000(a), nouns2000(b)
    assert len(words) == 1000, len(words)
    extras = json.load(open(EXTRAS, encoding="utf-8")) if os.path.exists(EXTRAS) else {}
    fix = lambda nid, key: extras.get(nid, {}).get(key)
    # Mexican words replace Spain ones before repeats are merged, so a replacement can merge too.
    spain = {}
    for i, (rank, es, en) in enumerate(words):
        nid = f"seed-es-top1000-{rank:04d}"
        if fix(nid, "spanish"): spain[nid] = es; words[i] = (rank, fix(nid, "spanish"), en)
    for i, (rank, en, es, label, notes) in enumerate(nouns):
        nid = f"seed-es-nouns2000-{rank:04d}"
        if fix(nid, "spanish"): spain[nid] = es
        nouns[i] = (rank, en, fix(nid, "spanish") or es, fix(nid, "gender_pos") or label, notes)
    words = merge_repeats(words, spanish=lambda r: r[1], english=lambda r: r[2])
    nouns = merge_repeats(nouns, spanish=lambda r: r[2], english=lambda r: r[1])
    ids = {f"seed-es-top1000-{r[0][0]:04d}" for r in words} | {f"seed-es-nouns2000-{r[0][0]:04d}" for r in nouns}
    spain_note = lambda nid: f". The list has \"{spain[nid]}\", the word used in Spain" if nid in spain else ""
    out = [
        "# Speed Cards starter deck: Spanish frequency lists. seed-version: 1",
        "# Generated by tools/seed/frequency.py from frequencylists.blogspot.com; edit the script, not this file.",
        "# Cards come up in list order. Both lists share the parent deck's daily limit, so studying",
        f"# \"{PARENT}\" works through the 1000 words before the first noun.",
        "# A word repeated within a list is one note, at its first rank.",
        "",
        f"@deck {DECK_1000}",
        "@type Spanish Vocab",
        "@tags frequency top1000",
        "@languages es-MX en-US",
    ]
    for (rank, es, en), more, others in words:
        nid = f"seed-es-top1000-{rank:04d}"
        en = fix(nid, "english") or ", ".join([en] + more)
        x = extra_fields(extras, nid, ids)
        out += ["", f"id: {nid}", f"Spanish: {es}", f"English: {en}", *x[:2],
                f"Notes: {ranks_note(rank, others, '1000 most used words')}{spain_note(nid)}", *x[2:]]
    out += [
        "",
        "# The source page has no #1849, so this list has 1999 nouns.",
        f"@deck {DECK_2000}",
        "@type Spanish Vocab",
        "@tags frequency nouns2000",
        "@languages es-MX en-US",
    ]
    for (rank, en, es, label, notes), more, others in nouns:
        nid = f"seed-es-nouns2000-{rank:04d}"
        en = fix(nid, "english") or ", ".join([en] + more)
        note = ranks_note(rank, others, "2000 most used nouns") + (f". {notes}" if notes and nid not in spain else "") + spain_note(nid)
        x = extra_fields(extras, nid, ids)
        out += ["", f"id: {nid}", f"Spanish: {es}", f"English: {en}", f"Gender / POS: {label}", *x[:2],
                f"Notes: {note}", *x[2:]]
    open(OUT, "w", encoding="utf-8").write("\n".join(out) + "\n")
    missing = [i for i in re.findall(r"^id: (.*)$", "\n".join(out), re.M) if i not in extras]
    print(f"{len(words)} words, {len(nouns)} nouns, {len(missing)} without an example -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
