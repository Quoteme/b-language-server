#!/usr/bin/env python3
"""Generates the hover and completion documentation of the language server from the official sources.

- eventb.json: the operator tables of the Rodin Handbook's chapter on the
  mathematical notation, with each operator's description, definition, types
  and well-definedness condition.
- classical.json: ProB's "Summary of B Syntax".

Each entry has a key (the keyword or operator it documents), a title, a
description, the details of Event-B operators, a link with its source, whether
the key names the entry rather than being part of it, and the operator's symbol.
The Kotlin class OperatorEntry reads them.

The script downloads its sources, so it is not part of the build: run it with
`./gradlew generateDocumentation` and commit the result.

Usage: python3 generate_documentation.py --out DIR [--symbols PATH]
  --symbols: Rodin's keyboard table, lua/prob/symbols.lua of b.nvim
"""

import argparse
import json
import html
import os
import re
import urllib.request

HANDBOOK = "https://www3.hhu.de/stups/handbook/rodin/current/html/"
SYNTAX = "https://prob.hhu.de/w/index.php?title=Summary_of_B_Syntax"


def fetch(url):
    with urllib.request.urlopen(url) as response:
        return response.read().decode("utf-8")


# ---------------------------------------------------------------------------
# LaTeX as used in the handbook, to Unicode

MACROS = {
    "in": "∈", "notin": "∉", "land": "∧", "lor": "∨", "lnot": "¬", "neg": "¬",
    "Rightarrow": "⇒", "Leftrightarrow": "⇔", "forall": "∀", "exists": "∃",
    "top": "⊤", "bot": "⊥", "neq": "≠", "leq": "≤", "geq": "≥", "le": "≤", "ge": "≥",
    "subseteq": "⊆", "subset": "⊂", "not": "¬", "cup": "∪", "cap": "∩", "setminus": "∖",
    "emptyset": "∅", "bigcup": "⋃", "bigcap": "⋂", "times": "×", "mapsto": "↦",
    "leftrightarrow": "↔", "rightarrow": "→", "leftarrow": "←", "twoheadrightarrow": "↠",
    "rightarrowtail": "↣", "circ": "∘", "otimes": "⊗", "lhd": "◁", "rhd": "▷",
    "lambda": "λ", "alpha": "α", "beta": "β", "gamma": "γ", "delta": "δ",
    "cdot": "·", "ldots": "…", "div": "÷", "bmod": "mod", "mid": "∣", "min": "min", "max": "max",
    "Box": "□", "quad": " ", "enskip": " ", "mathcal": "", "widehat": "",
}

SYMBOLS = {
    r"\mathbb N": "ℕ", r"\mathbb Z": "ℤ", r"\mathbb P": "ℙ",
}


def latex(text):
    """Turns the handbook's LaTeX into readable Unicode text."""
    text = html.unescape(text)
    # Composite arrows, drawn from several symbols.
    text = re.sub(r"\\rightarrowtail\s*(?:\\mkern\s*-?[0-9.]+mu\s*)*\\twoheadrightarrow", "⤖", text)
    gap = r"\s*(?:\\mkern\s*-?[0-9.]+mu\s*)*"
    text = re.sub(r"\\mapstochar" + gap + r"\\rightarrowtail", "⤔", text)
    text = re.sub(r"\\mapstochar" + gap + r"\\twoheadrightarrow", "⤀", text)
    text = re.sub(r"\\mapstochar" + gap + r"\\rightarrow", "⇸", text)
    text = re.sub(r"\\mathcal\{L\}", "L", text)
    # Set braces survive; grouping braces do not.
    text = text.replace("\\{", "\x01").replace("\\}", "\x02")
    text = re.sub(r"\\mathrel\s*\{\\widehat=\}", "≙", text)
    text = re.sub(r"\\widehat=", "≙", text)
    text = re.sub(r"\\mathbin\s*\{\.\\mkern\s*1mu\.\}", "‥", text)
    text = re.sub(r"\\mathbin\s*\{\\widehat\{\\enskip\s*\}\}", "^", text)
    text = re.sub(r"\\mathbb\s*\{?([NZP])\}?", lambda m: SYMBOLS["\\mathbb " + m.group(1)], text)
    text = re.sub(r"\\mathbb\s*\{?([NZP])\}?1", lambda m: SYMBOLS["\\mathbb " + m.group(1)] + "1", text)
    text = re.sub(r"\\mkern\s*-?[0-9.]+mu", "", text)
    text = re.sub(r"\\raisebox\{[^}]*\}", "", text)
    text = re.sub(r"\\(?:hbox|mbox)\{\}", "", text)
    text = re.sub(r"\\not\s*\\in", "∉", text)
    text = re.sub(r"\\not\s*\\subseteq", "⊈", text)
    text = re.sub(r"\\not\s*\\subset", "⊄", text)
    text = re.sub(r"\\mapstochar\s*\\rightarrow", "⇸", text)
    text = re.sub(r"\\(textsf|textsl|textit|mathrm|mathsf|ensuremath|mathop|mathbin|mathrel|mathord|nolimits)\b", "", text)
    text = re.sub(r"\\([A-Za-z]+)", lambda m: MACROS.get(m.group(1), ""), text)
    text = text.replace("$", "").replace("{", "").replace("}", "").replace("~", " ")
    text = text.replace("\x01", "{").replace("\x02", "}")
    text = re.sub(r"\\[,;! ]", " ", text)
    text = text.replace("\\", "")
    text = re.sub(r"\s*_\s*([0-9A-Za-z])", r"\1", text)
    return tidy(text)


BINARY = "∈∉⊆⊂⊈⊄∪∩∖↦×⇒⇔∧∨=≠≤≥<>+·÷⇸→⤔↣⤀↠⤖↔◁▷⩤⩥∘⊗∥≙∣\ue100\ue101\ue102\ue103"


def tidy(text):
    """Spaces formulas the way Rodin prints them: around binary operators, not inside brackets."""
    text = re.sub(r"\s+", " ", text)
    text = re.sub(r"\s*([" + BINARY + r"])\s*", r" \1 ", text)
    text = re.sub(r"([(\[{])\s+", r"\1", text)
    text = re.sub(r"\s+([)\]},])", r"\1", text)
    # Function applications, but not prose like "injective (is an injection)".
    text = re.sub(r"(?<![A-Za-z])([A-Za-z]|ℙ1?|L|dom|ran|card|finite|min|max|union|inter|bool|id|prj[12]|partition)\s+\(", r"\1(", text)
    text = re.sub(r"\s+'", "'", text)
    # Assignments and the override symbol are drawn from two symbols each.
    text = re.sub(r":\s*=", "≔", text)
    text = re.sub(r":\s*∣", ":∣", text)
    text = re.sub(r":\s*∈", ":∈", text)
    text = re.sub(r"◁\s*-", "\ue103", text)
    text = re.sub(r"‥\s+", "‥", text)
    return re.sub(r"\s+", " ", text).strip()


def plain(fragment):
    """Text of an HTML fragment, with line breaks kept as ' ; '."""
    fragment = re.sub(r"<br\s*/?>", "\n", fragment)
    fragment = re.sub(r"</p>\s*<p>", "\n", fragment)
    fragment = re.sub(r"<[^>]+>", "", fragment)
    lines = [latex(line) for line in fragment.split("\n")]
    return " — ".join(line for line in lines if line)


# ---------------------------------------------------------------------------
# Rodin Handbook

# Separates the details of an entry; "|" occurs in the formulas.
DETAILS = " \u2016 "

PAGES = ["predicates", "booleans", "sets", "relations", "arithmetic", "typing", "assignments"]


def keyboard(path):
    """Rodin's keyboard table (b.nvim's lua/prob/symbols.lua): ASCII combination to symbol."""
    table = {}
    for combo, symbol in re.findall(r"\{ '((?:[^'\\]|\\.)*)', '((?:[^'\\]|\\.)*)'", open(path, encoding="utf-8").read()):
        combo = combo.replace("\\\\", "\\")
        symbol = re.sub(r"\\u\{([0-9A-Fa-f]+)\}", lambda m: chr(int(m.group(1), 16)), symbol)
        table.setdefault(combo, symbol)
    return table


def handbook(symbols_path):
    rows = []
    symbols = keyboard(symbols_path)
    for page in PAGES:
        source = fetch(HANDBOOK + page + ".html")
        # Formulas are images whose alt text is their LaTeX source.
        source = re.sub(r'<img[^>]*alt="([^"]*)"[^>]*>', lambda m: m.group(1).replace("<", "&lt;"), source)
        # Each section starts with a heading; its operators are in a table followed by a description list.
        for section in re.split(r"(?=<h[234] id=)", source):
            heading = re.match(r'<h[234] id="([^"]+)">([^<]+)</h[234]>', section)
            if not heading:
                continue
            anchor, title = heading.group(1), html.unescape(heading.group(2)).strip()
            link = f"{HANDBOOK}{page}.html#{anchor}"
            where = f"Rodin Handbook, {title}"
            fields = dict(
                (latex(re.sub(r"<[^>]+>", "", dt)), plain(dd))
                for dt, dd in re.findall(r"<dt>(.*?)</dt>\s*<dd>(.*?)</dd>", section, re.S)
            )
            for table in re.findall(r'<table class="tabular".*?</table>', section, re.S):
                for row in re.findall(r"<tr>(.*?)</tr>", table, re.S):
                    cells = [c for c in re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)]
                    if len(cells) < 3:
                        continue
                    symbol = latex(re.sub(r"<[^>]+>", "", cells[0]))
                    # The HTML renders "--" as an en dash.
                    ascii_ = html.unescape(re.sub(r"<[^>]+>", "", cells[1])).strip().replace("–", "--")
                    name = latex(re.sub(r"<[^>]+>", "", cells[2]))
                    # Truth tables list values, not operators.
                    if not name or name in ("⊤", "⊥"):
                        continue
                    details = []
                    if ascii_:
                        details.append(f"ASCII: `{ascii_}`")
                    for field in ("Definition", "Types", "WD", "Example"):
                        if fields.get(field):
                            details.append(f"{field}: {fields[field]}")
                    description = fields.get("Description", "")
                    # Rodin's symbol for the ASCII input; the LaTeX only approximates some arrows.
                    symbol = symbols.get(ascii_, symbol)
                    for key in dict.fromkeys([symbol, ascii_]):
                        if key and " " not in key and "$" not in key and not re.fullmatch(r"[A-Za-z]|exprs|\{.*\}.+|\{(?:exprs|E\|P|ids\..*)\}|…|\(…\)|\[…\]", key):
                            rows.append((key, name, description, DETAILS.join(details), link, where, "1", symbol))
    return rows


# ---------------------------------------------------------------------------
# ProB's Summary of B Syntax

PLACEHOLDER = re.compile(r"^(?:[A-Za-z]|[A-Za-z][0-9n]|ID|tl|tr|Dom|Ran|Pair|OP|BODY|NAME[0-9]?|LBL|DESC|PATH|PRAGMA|VALUE|SETNAME|OPNAME|FILE|Expression|E[0-9n]?|G[0-9n]?|P[0-9n]?|x[0-9n]?|r[0-9]|ss|cx|cy|cv|cw|e[0-9]|x[0-9]|astring|tstring|Expr)$")


LOWERCASE_WORDS = {"mod", "or", "not", "btrue", "bfalse", "skip"}


def keys_of(syntax):
    """The keywords, built-in functions and operators of an entry's syntax."""
    keys = []
    # "S-T or S \ T" lists alternatives; in "P or Q", or is the operator.
    alternatives = [syntax] if syntax == "P or Q" else re.split(r"\s+or\s+", syntax)
    for alternative in (re.sub(r'"[^"]*"', "", a) for a in alternatives):
        for word in re.findall(r"[A-Za-z_][A-Za-z0-9_]*", alternative):
            keyword = re.fullmatch(r"[A-Z][A-Z0-9_]+", word) is not None
            function = re.search(re.escape(word) + r"\(", alternative) is not None and word[0].islower()
            if PLACEHOLDER.match(word) or not (keyword or function or word in LOWERCASE_WORDS):
                continue
            if word not in keys:
                keys.append(word)
        for operator in re.findall(r"[^\sA-Za-z0-9_(),.\[\]{}'\"`$]+", alternative):
            if operator not in keys and operator not in ("...", "…", "_", "•"):
                keys.append(operator)
    if not keys:
        # Constructs made of brackets and dots alone: n..m, r[S], [E], E'ID.
        for operator in re.findall(r"(?<!\.)\.\.(?!\.)|[\['<>]", syntax):
            if operator not in keys:
                keys.append(operator)
    return keys


# Sections of explanations rather than syntax.
PROSE = {"Differences with AtelierB/B4Free", "Other notes", "Free Types", "Event-B Syntax", "Comments and Pragmas", "Identifiers"}


def syntax_summary():
    rows = []
    raw = fetch(SYNTAX + "&action=raw")
    section = "Summary of B Syntax"
    in_pre = False
    for line in raw.splitlines():
        heading = re.match(r"^===\s*(.+?)\s*===\s*$", line)
        if heading:
            section = heading.group(1)
            continue
        if "<pre>" in line:
            in_pre = True
            line = line.split("<pre>", 1)[1]
        if "</pre>" in line:
            in_pre = False
            continue
        if not in_pre or section in PROSE or not line.strip() or not line.startswith(" ") or line.startswith("  " * 6):
            continue
        parts = re.split(r"\s{2,}", line.strip(), maxsplit=1)
        syntax = parts[0]
        description = parts[1] if len(parts) > 1 else ""
        # Machine sections have three columns: keyword, its argument, a description in parentheses.
        if section == "Machine sections":
            argument = re.split(r"\s{2,}", description, maxsplit=1)
            syntax = f"{syntax} {argument[0]}"
            description = argument[1].strip("()") if len(argument) == 2 else ""
        # Explanations continued inside the tables, such as "mirror, infix are recognised by …".
        if syntax.startswith("-") or len(re.findall(r"\b[a-z]{3,}\b", syntax)) >= 4:
            continue
        link = SYNTAX + "#" + section.replace(" ", "_")
        where = f"ProB, Summary of B Syntax: {section}"
        keys = keys_of(syntax)
        for key in keys:
            # The first key names the construct; THEN in IF … THEN … END does not.
            rows.append((key, syntax, description, "", link, where, "1" if key == keys[0] else "", ""))
    return rows


def lines(field):
    """The lines of a detail, which the handbook separates by " — "."""
    return [line.strip() for line in field.split(" — ") if line.strip()]


def entry(row):
    """One documented key, as the language server reads it."""
    key, title, description, details, link, source, primary, symbol = row
    fields = {}
    for part in details.split(DETAILS):
        if ":" in part:
            name, _, value = part.partition(":")
            fields[name.strip()] = value.strip()
    return {
        "key": key,
        "title": title,
        "description": description,
        "ascii": fields.get("ASCII"),
        "definition": lines(fields.get("Definition", "")),
        "types": lines(fields.get("Types", "")),
        "wd": lines(fields.get("WD", "")),
        "link": link,
        "source": source,
        "primary": primary == "1",
        "symbol": symbol,
    }


def write(directory, name, rows):
    os.makedirs(directory, exist_ok=True)
    entries = [entry(tuple(field.replace("\t", " ").replace("\n", " ") for field in row)) for row in rows]
    with open(os.path.join(directory, name), "w", encoding="utf-8") as out:
        json.dump(entries, out, ensure_ascii=False, indent=1)
        out.write("\n")


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True, help="directory to write eventb.json and classical.json to")
    parser.add_argument(
        "--symbols",
        default=os.path.join(here, "..", "..", "b.nvim", "lua", "prob", "symbols.lua"),
        help="Rodin's keyboard table, lua/prob/symbols.lua of b.nvim",
    )
    arguments = parser.parse_args()
    write(arguments.out, "eventb.json", handbook(arguments.symbols))
    write(arguments.out, "classical.json", syntax_summary())
