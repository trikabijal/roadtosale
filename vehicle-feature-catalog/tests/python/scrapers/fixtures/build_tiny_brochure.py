"""Generate a tiny synthetic brochure PDF for extractor tests.

Run with reportlab installed:

    python tests/python/scrapers/fixtures/build_tiny_brochure.py

Output: ``tests/python/scrapers/fixtures/tiny_brochure.pdf``. The shape mimics
a Honda spec grid (header row of trim names, body rows of feature x glyph)
but contains zero verbatim Honda content — it's a stand-in.
"""

from __future__ import annotations

from pathlib import Path

from reportlab.lib.pagesizes import letter
from reportlab.lib import colors
from reportlab.platypus import SimpleDocTemplate, Table, TableStyle, Paragraph, Spacer
from reportlab.lib.styles import getSampleStyleSheet


def build(out_path: Path) -> None:
    styles = getSampleStyleSheet()
    doc = SimpleDocTemplate(str(out_path), pagesize=letter, title="Synthetic Brochure")

    story = []
    story.append(Paragraph("Synthetic Test Vehicle — 2026", styles["Title"]))
    story.append(Spacer(1, 12))
    story.append(
        Paragraph(
            "This PDF exists only to exercise the PDF table-extractor. "
            "Any resemblance to a real Honda brochure is incidental.",
            styles["BodyText"],
        )
    )
    story.append(Spacer(1, 24))

    # Header row: empty label cell + 3 trim columns
    data = [
        ["Feature", "LX", "EX", "Touring"],
        ["Wireless Apple CarPlay", "S", "S", "S"],
        ["Heated Front Seats", "—", "S", "S"],
        ["Honda Sensing", "S", "S", "S"],
        ["LED Headlights", "—", "S", "S"],
        ["Panoramic Moonroof", "—", "—", "S"],
        ["Ventilated Front Seats", "—", "—", "S"],
        ["Bose Premium Audio", "—", "O", "S"],
    ]
    table = Table(data, colWidths=[180, 80, 80, 80])
    table.setStyle(
        TableStyle(
            [
                ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
                ("BACKGROUND", (0, 0), (-1, 0), colors.lightgrey),
                ("GRID", (0, 0), (-1, -1), 0.4, colors.grey),
                ("ALIGN", (1, 0), (-1, -1), "CENTER"),
            ]
        )
    )
    story.append(table)
    doc.build(story)


if __name__ == "__main__":
    target = Path(__file__).resolve().parent / "tiny_brochure.pdf"
    build(target)
    print(f"Wrote {target} ({target.stat().st_size} bytes)")
