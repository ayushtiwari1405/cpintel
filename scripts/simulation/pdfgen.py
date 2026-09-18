"""
A minimal PDF writer, with no dependencies.

DOMjudge serves problem statements as PDFs from the problem package, and CPIntel's arena
embeds whatever it gets. Testing that path needs *real* PDFs — a placeholder file with a .pdf
extension renders as a broken frame and proves nothing about the thing we are trying to
exercise.

Rather than pull in reportlab or a LaTeX toolchain for a simulation harness, this writes the
PDF by hand. That is entirely reasonable at this scale: a text-only document in one of the
14 standard fonts needs no font embedding, no compression and no images, which is most of what
makes PDF writing hard. What is left is a handful of objects and a correct cross-reference
table.

Deliberately not a general-purpose library. It lays out left-aligned text in three weights on
US Letter, wraps at a fixed column, breaks pages, and stops there.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Tuple

# US Letter, in PostScript points.
PAGE_W, PAGE_H = 612, 792
MARGIN = 64

FONTS = {
    "regular": "F1",   # Helvetica
    "bold": "F2",      # Helvetica-Bold
    "mono": "F3",      # Courier
}

# Average glyph width as a fraction of font size, used only to decide where to wrap.
# Helvetica is roughly 0.5 em on mixed-case prose; Courier is exactly 0.6 em.
_WIDTH_FACTOR = {"regular": 0.50, "bold": 0.53, "mono": 0.60}


@dataclass
class Line:
    """One laid-out line: its text, the font to set, and the size."""
    text: str
    style: str
    size: float
    space_after: float


class Pdf:
    """Accumulates lines, then serialises them as a paginated PDF."""

    def __init__(self) -> None:
        self.lines: List[Line] = []

    # ------------------------------------------------------------- authoring

    def heading(self, text: str, size: float = 17) -> None:
        self.lines.append(Line(text, "bold", size, 10))

    def subheading(self, text: str, size: float = 12) -> None:
        self.lines.append(Line(text, "bold", size, 5))

    def para(self, text: str, size: float = 10.5) -> None:
        for chunk in self._wrap(text, "regular", size):
            self.lines.append(Line(chunk, "regular", size, 2))
        self.lines.append(Line("", "regular", size, 6))

    def mono(self, text: str, size: float = 9.5) -> None:
        """Pre-formatted text — sample data, mostly. Never re-wrapped."""
        for raw in text.split("\n"):
            self.lines.append(Line(raw, "mono", size, 1.5))
        self.lines.append(Line("", "mono", size, 5))

    def bullet(self, text: str, size: float = 10.5) -> None:
        wrapped = self._wrap(text, "regular", size, indent=2)
        for i, chunk in enumerate(wrapped):
            prefix = "•  " if i == 0 else "   "
            self.lines.append(Line(prefix + chunk, "regular", size, 2))

    def gap(self, height: float = 8) -> None:
        self.lines.append(Line("", "regular", 10, height))

    def _wrap(self, text: str, style: str, size: float, indent: int = 0) -> List[str]:
        usable = PAGE_W - 2 * MARGIN - indent * 6
        per_char = size * _WIDTH_FACTOR[style]
        limit = max(20, int(usable / per_char))

        out: List[str] = []
        for paragraph in text.split("\n"):
            words = paragraph.split()
            if not words:
                out.append("")
                continue
            current = words[0]
            for word in words[1:]:
                if len(current) + 1 + len(word) <= limit:
                    current += " " + word
                else:
                    out.append(current)
                    current = word
            out.append(current)
        return out

    # ------------------------------------------------------------ page layout

    def _paginate(self) -> List[List[Tuple[Line, float]]]:
        """Splits the lines into pages, each a list of (line, baseline y)."""
        pages: List[List[Tuple[Line, float]]] = []
        current: List[Tuple[Line, float]] = []
        y = PAGE_H - MARGIN

        for line in self.lines:
            advance = line.size + line.space_after
            if y - advance < MARGIN:
                pages.append(current)
                current = []
                y = PAGE_H - MARGIN
            y -= line.size
            current.append((line, y))
            y -= line.space_after

        if current:
            pages.append(current)
        return pages or [[]]

    # ------------------------------------------------------------ serialising

    #: Typographic characters that prose picks up naturally but Latin-1 cannot hold.
    #: Transliterated rather than dropped: an em-dash rendering as "?" in the middle of a
    #: problem statement looks like a corrupted file to whoever is reading it under time
    #: pressure.
    _TRANSLITERATE = {
        "\u2014": " - ", "\u2013": "-", "\u2018": "'", "\u2019": "'",
        "\u201c": '"', "\u201d": '"', "\u2026": "...", "\u00a0": " ",
        "\u2264": "<=", "\u2265": ">=", "\u2192": "->", "\u00d7": "x",
        "\u2022": "-",
    }

    @classmethod
    def _escape(cls, text: str) -> str:
        for src, dst in cls._TRANSLITERATE.items():
            text = text.replace(src, dst)
        # Backslash first, or the escapes this adds would themselves be escaped.
        out = text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")
        # Whatever is still outside Latin-1 cannot be encoded; a question mark is the least
        # confusing stand-in and by here it can only be something exotic.
        return "".join(c if ord(c) < 256 else "?" for c in out)

    def _content_stream(self, page: List[Tuple[Line, float]]) -> bytes:
        parts = ["BT"]
        last_font = None
        for line, y in page:
            if not line.text:
                continue
            font = (FONTS[line.style], line.size)
            if font != last_font:
                parts.append(f"/{font[0]} {font[1]:.1f} Tf")
                last_font = font
            parts.append(f"1 0 0 1 {MARGIN} {y:.1f} Tm")
            parts.append(f"({self._escape(line.text)}) Tj")
        parts.append("ET")
        return "\n".join(parts).encode("latin-1", "replace")

    def build(self) -> bytes:
        pages = self._paginate()

        # Object numbering: 1 catalog, 2 pages tree, 3-5 fonts, then per page a page object
        # and a content stream.
        n_pages = len(pages)
        first_page_obj = 6
        page_ids = [first_page_obj + 2 * i for i in range(n_pages)]
        content_ids = [first_page_obj + 2 * i + 1 for i in range(n_pages)]

        objects: dict[int, bytes] = {}

        kids = " ".join(f"{pid} 0 R" for pid in page_ids)
        objects[1] = b"<< /Type /Catalog /Pages 2 0 R >>"
        objects[2] = (
            f"<< /Type /Pages /Count {n_pages} /Kids [{kids}] >>".encode()
        )
        objects[3] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        objects[4] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>"
        objects[5] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>"

        for i, page in enumerate(pages):
            stream = self._content_stream(page)
            objects[page_ids[i]] = (
                f"<< /Type /Page /Parent 2 0 R "
                f"/MediaBox [0 0 {PAGE_W} {PAGE_H}] "
                f"/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> "
                f"/Contents {content_ids[i]} 0 R >>"
            ).encode()
            objects[content_ids[i]] = (
                b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n"
                + stream + b"\nendstream"
            )

        # Body, recording each object's byte offset for the xref table.
        out = bytearray(b"%PDF-1.4\n")
        offsets: dict[int, int] = {}
        for num in sorted(objects):
            offsets[num] = len(out)
            out += f"{num} 0 obj\n".encode() + objects[num] + b"\nendobj\n"

        xref_at = len(out)
        highest = max(objects)
        out += f"xref\n0 {highest + 1}\n".encode()
        out += b"0000000000 65535 f \n"
        for num in range(1, highest + 1):
            # Every slot 1..highest is populated by construction; the check keeps a future
            # edit that leaves a hole from silently producing a corrupt file.
            if num not in offsets:
                raise AssertionError(f"object {num} missing from the PDF body")
            out += f"{offsets[num]:010d} 00000 n \n".encode()

        out += (
            f"trailer\n<< /Size {highest + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref_at}\n%%EOF\n"
        ).encode()
        return bytes(out)
