#!/usr/bin/env python3
"""Checks the bundled starter decks (app/src/main/assets/seed/*.sctxt).

Usage: python validate.py [--csv OUTDIR] [--es-dict PATH_WITHOUT_EXTENSION]
  --es-dict: Hunspell es_MX dictionary (es_MX.dic/.aff from github.com/LibreOffice/dictionaries, folder "es").
Errors (exit 1): missing fields, duplicate ids/headwords, broken links, bad cloze markup.
Warnings: pinyin that doesn't match the characters, Simplified/Traditional mismatches,
words a Spanish spell checker doesn't know. Warnings need a human look, not always a fix.
"""
import csv, os, re, sys, unicodedata
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
SEED = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "seed")
ALLOWED_LINKS = {"synonym", "antonym", "gender pair", "same root", "collocation", "plural of", "false friend", "same measure word"}
REQUIRED = {
    "Spanish Vocab": ["Spanish", "English", "Gender / POS", "Example (ES)", "Example (EN)"],
    "Chinese Vocab": ["Simplified", "Traditional", "Pinyin", "English", "Example (Simplified)", "Example (Traditional)", "Example pinyin", "Example (EN)"],
}
# Word lists (tools/seed/frequency.py): no example sentences, and repeated words are kept on purpose.
WORD_LISTS = {"spanish_mx_frequency.sctxt"}
WORD_LIST_REQUIRED = ["Spanish", "English"]


def parse(path):
    """Minimal .sctxt reader mirroring SctxtParser (blank line or --- separates notes)."""
    notes, cur, deck, typ, tags = [], None, None, None, []
    for no, raw in enumerate(open(path, encoding="utf-8"), 1):
        line = raw.rstrip("\n")
        if not line.strip() or line.strip() == "---":
            if cur: notes.append(cur); cur = None
            continue
        if line.startswith("#"): continue
        if line.startswith("@"):
            key, _, val = line[1:].partition(" ")
            if key == "deck": deck = val
            elif key == "type": typ = val
            elif key == "tags": tags = val.split()
            continue
        if cur is None: cur = {"_line": no, "_deck": deck, "_type": typ, "_tags": tags, "fields": {}}
        m = re.match(r"^([^:]+?)\s*:\s?(.*)$", line)
        if not m: raise SystemExit(f"{path}:{no}: can't parse: {line}")
        cur["fields"][m.group(1)] = m.group(2)
    if cur: notes.append(cur)
    return notes


def links(value):
    out = []
    for part in value.split(";"):
        t = part.split("[[")[0].strip()
        out += [(t, x) for x in re.findall(r"\[\[([^\]]+)\]\]", part)]
    return out


def strip_tones(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn").replace("ü", "v")


def check(path, errors, warnings):
    notes = parse(path)
    name = os.path.basename(path)
    ids = Counter(n["fields"].get("id") for n in notes)
    for i, c in ids.items():
        if c > 1: errors.append(f"{name}: duplicate id {i}")
    for n in notes:
        f, where = n["fields"], f"{name}:{n['_line']} ({n['fields'].get('id')})"
        required = WORD_LIST_REQUIRED if name in WORD_LISTS else REQUIRED.get(n["_type"], [])
        for req in ["id"] + required:
            if not f.get(req, "").strip(): errors.append(f"{where}: missing {req}")
        for t, target in links(f.get("links", "")):
            if t not in ALLOWED_LINKS: errors.append(f"{where}: link type '{t}' not allowed")
            if target not in ids: errors.append(f"{where}: link target {target} not found")
    head = "Spanish" if notes and notes[0]["_type"] == "Spanish Vocab" else "Simplified"
    if name not in WORD_LISTS:
        for w, c in Counter(n["fields"].get(head) for n in notes).items():
            if c > 1: errors.append(f"{name}: duplicate headword {w}")
    if head == "Spanish": check_spanish(notes, name, errors, warnings)
    else: check_chinese(notes, name, errors, warnings)
    counts = Counter(n["_deck"] for n in notes)
    return notes, counts


def check_spanish(notes, name, errors, warnings):
    for n in notes:
        f, where = n["fields"], f"{name}:{n['_line']} ({n['fields'].get('id')})"
        ex = f.get("Example (ES)", "")
        if name in WORD_LISTS and not ex: continue
        if len(re.findall(r"\{\{c1::", ex)) != 1: errors.append(f"{where}: Example (ES) needs exactly one {{{{c1::…}}}}")
    if not ES_DICT:
        warnings.append("Spanish spell check skipped (pass --es-dict)")
        return
    from spylls.hunspell import Dictionary
    d = Dictionary.from_files(ES_DICT)
    for n in notes:
        f, where = n["fields"], f"{name}:{n['_line']} ({n['fields'].get('id')})"
        text = re.sub(r"\{\{c\d+::(.*?)\}\}", r"\1", f.get("Spanish", "") + " " + f.get("Example (ES)", ""))
        words = set(re.findall(r"[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+", text))
        unknown = sorted(w for w in words if not d.lookup(w) and not d.lookup(w.lower()))
        if unknown: warnings.append(f"{where}: not in es_MX dictionary: {', '.join(unknown)}")


def check_chinese(notes, name, errors, warnings):
    from opencc import OpenCC
    from pypinyin import pinyin, Style
    converters = [OpenCC(c) for c in ("s2t", "s2tw", "s2twp", "s2hk")]
    for n in notes:
        f, where = n["fields"], f"{name}:{n['_line']} ({n['fields'].get('id')})"
        for s_key, t_key in [("Simplified", "Traditional"), ("Example (Simplified)", "Example (Traditional)")]:
            s, t = f.get(s_key, ""), f.get(t_key, "")
            if len(s) != len(t): errors.append(f"{where}: {s_key}/{t_key} lengths differ")
            elif t not in {c.convert(s) for c in converters}:
                ref = converters[1].convert(s)
                diffs = [f"{a}->{b} (OpenCC s2tw {c})" for a, b, c in zip(s, t, ref) if b != c]
                if diffs: warnings.append(f"{where}: Traditional differs from OpenCC: {'; '.join(diffs)}")
        # Pinyin: each character must match one of its dictionary readings. Base tones are accepted for
        # 不 and 一 (sandhi isn't written), unmarked syllables count as neutral tone, 儿 may be erhua "r".
        for h_key, p_key in [("Simplified", "Pinyin"), ("Example (Simplified)", "Example pinyin")]:
            chars = [c for c in f.get(h_key, "") if "\u4e00" <= c <= "\u9fff"]
            readings = pinyin("".join(chars), style=Style.TONE, heteronym=True)
            given = unicodedata.normalize("NFC", f.get(p_key, "")).lower()
            given = re.sub(r"[^a-zāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜü]", "", given)
            pos, ok = 0, True
            for ch, opts in zip(chars, readings):
                opts = list(opts)
                if ch == "不": opts += ["bù", "bú", "bu"]
                if ch == "一": opts += ["yī", "yí", "yì"]
                if ch == "儿": opts += ["r"]
                cands = sorted(set(opts + [strip_tones(o).replace("v", "ü") for o in opts]), key=len, reverse=True)
                match = next((o for o in cands if given.startswith(o, pos)), None)
                if match is None: ok = False; break
                pos += len(match)
            if not ok or pos != len(given):
                expected = " ".join("/".join(o) for o in readings)
                warnings.append(f"{where}: {p_key} '{f.get(p_key)}' vs dictionary: {expected}")


def write_csv(notes, path):
    keys = ["id"] + sorted({k for n in notes for k in n["fields"]} - {"id"}, key=lambda k: list(notes[0]["fields"]).index(k) if k in notes[0]["fields"] else 99)
    with open(path, "w", newline="", encoding="utf-8-sig") as fh:
        w = csv.writer(fh)
        w.writerow(["deck"] + keys)
        for n in notes: w.writerow([n["_deck"]] + [n["fields"].get(k, "") for k in keys])


ES_DICT = None


def main():
    global ES_DICT
    errors, warnings = [], []
    ES_DICT = sys.argv[sys.argv.index("--es-dict") + 1] if "--es-dict" in sys.argv else None
    out = sys.argv[sys.argv.index("--csv") + 1] if "--csv" in sys.argv else None
    for fn in sorted(os.listdir(SEED)):
        if not fn.endswith(".sctxt"): continue
        notes, counts = check(os.path.join(SEED, fn), errors, warnings)
        print(f"{fn}: {len(notes)} notes, {sum(len(links(n['fields'].get('links',''))) for n in notes)} links")
        for d, c in counts.items(): print(f"   {d}: {c}")
        if out:
            os.makedirs(out, exist_ok=True)
            write_csv(notes, os.path.join(out, fn.replace(".sctxt", ".csv")))
    print(f"\n{len(errors)} errors, {len(warnings)} warnings")
    for e in errors: print("ERROR", e)
    for w in warnings: print("WARN ", w)
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
