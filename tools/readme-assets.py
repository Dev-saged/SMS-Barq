#!/usr/bin/env python3
"""Builds the README artwork in docs/readme (SVG only, fonts embedded as subsets).

Run from the repo root: python3 tools/readme-assets.py   (needs: pip install fonttools brotli)
"""
import base64, io, pathlib, re
from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
FONTS = ROOT / "app/src/main/res/font"
OUT = ROOT / "docs/readme"
VERSION = re.search(r"VERSION_NAME=(\S+)", (ROOT / "gradle.properties").read_text()).group(1)

PRIMARY, DEEP, INK, MUTE, PAGE, CARD = "#4355B9", "#293CA0", "#1B1B21", "#5F6070", "#EFEFF6", "#FFFFFF"


def font_face(name, file, text):
    options = subset.Options()
    options.layout_features = ["*"]
    options.flavor = "woff2"
    font = TTFont(FONTS / file)
    sub = subset.Subsetter(options)
    sub.populate(text=text + " ")
    sub.subset(font)
    buf = io.BytesIO()
    font.flavor = "woff2"
    font.save(buf)
    data = base64.b64encode(buf.getvalue()).decode()
    return f"@font-face{{font-family:'{name}';src:url(data:font/woff2;base64,{data}) format('woff2');}}"


class Svg:
    def __init__(self, w, h):
        self.w, self.h, self.body, self.bold, self.regular = w, h, [], "", ""

    def add(self, s):
        self.body.append(s)

    def text(self, x, y, s, size, fill, bold=False, anchor="start", rtl=True, opacity=1):
        if bold:
            self.bold += s
        else:
            self.regular += s
        family = "B7" if bold else "B4"
        op = f' fill-opacity="{opacity}"' if opacity != 1 else ""
        self.add(
            f'<text x="{x}" y="{y}" font-family="{family}, \'Segoe UI\', Tahoma, sans-serif" font-size="{size}" '
            f'fill="{fill}"{op} text-anchor="{anchor}" direction="{"rtl" if rtl else "ltr"}">{s}</text>'
        )

    def save(self, name):
        faces = ""
        if self.bold:
            faces += font_face("B7", "readex_bold.ttf", self.bold)
        if self.regular:
            faces += font_face("B4", "readex_regular.ttf", self.regular)
        style = f"<style>{faces}</style>" if faces else ""
        (OUT / name).write_text(
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" viewBox="0 0 {self.w} {self.h}">'
            f"{style}{''.join(self.body)}</svg>",
            encoding="utf-8",
        )


GRADIENT = (
    '<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#4F63E6"/>'
    '<stop offset="0.55" stop-color="#2F3BA8"/><stop offset="1" stop-color="#121747"/></linearGradient>'
    '<radialGradient id="glow" cx="0.9" cy="0" r="0.9"><stop offset="0" stop-color="#9AA8FF" stop-opacity="0.35"/>'
    '<stop offset="1" stop-color="#9AA8FF" stop-opacity="0"/></radialGradient></defs>'
)

SYMBOL = (
    '<g transform="translate(0,-7)"><g transform="translate(-6,1)">'
    '<path fill="{a}" d="M133.75,40L92,40A56,56 0 0 0 36,96L36,140A56,56 0 0 0 92,196L102.8,196L122,124L90,124Z"/>'
    '<path fill="{a}" d="M68.03,176.89A2,2 0 0 0 65.32,178.1L53.02,216.24A6,6 0 0 0 62.8,222.49L95.75,192.08A2,2 0 0 0 95.2,188.77Z"/></g>'
    '<g transform="translate(6,-9)"><path fill="{b}" d="M157,40L164,40A56,56 0 0 1 220,96L220,140A56,56 0 0 1 164,196L108.7,196L166,100L132,100Z"/></g></g>'
)

# 24-grid line icons, one family: 1.9 stroke, round caps and joins.
ICONS = {
    "reply": ["M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v7a2.5 2.5 0 0 1-2.5 2.5H10l-4 3.5V16A2.5 2.5 0 0 1 4 13.5z",
              "M12 7.3l.8 2.1l2.1.8l-2.1.8l-.8 2.1l-.8-2.1l-2.1-.8l2.1-.8z"],
    "globe": ["M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M3 12h18", "M12 3c2.9 3 2.9 15 0 18c-2.9-3-2.9-15 0-18z"],
    "eye": ["M2.5 12c2.6-4.4 6-6.5 9.5-6.5s6.9 2.1 9.5 6.5c-2.6 4.4-6 6.5-9.5 6.5S5.1 16.4 2.5 12z",
            "M12 9.2a2.8 2.8 0 1 0 0 5.6a2.8 2.8 0 1 0 0-5.6z"],
    "key": ["M14.5 4a5.5 5.5 0 1 0 0 11a5.5 5.5 0 1 0 0-11z", "M10.6 13.4L4 20", "M6.5 17.5l2.2 2.2", "M15.8 8.2h.1"],
    "tag": ["M3.5 12.2V4.5a1 1 0 0 1 1-1h7.7l8.3 8.3a1 1 0 0 1 0 1.4l-7 7a1 1 0 0 1-1.4 0z", "M8 8h.1"],
    "send": ["M20.5 3.5L3.5 10.2l6.3 2.6l2.6 6.3z", "M20.5 3.5L9.8 12.8"],
    "people": ["M9 5a3.2 3.2 0 1 0 0 6.4A3.2 3.2 0 1 0 9 5z", "M3 19.5c.4-3.3 2.9-5.2 6-5.2s5.6 1.9 6 5.2",
               "M16 5.300a3 3 0 0 1 0 5.8", "M17.5 14.600c2 .60 3.2 2.3 3.5 4.9"],
    "clock": ["M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 7.500V12l3 2"],
    "search": ["M17 10.5a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0z", "M15.3 15.300L20 20"],
    "backup": ["M7 18.500a4.5 4.5 0 0 1-.60-8.950a6 6 0 0 1 11.6 1.450a3.75 3.75 0 0 1-.50 7.5", "M12 20v-8", "M9 14.500l3-3l3 3"],
    "lock": ["M6.5 10.500h11a1.5 1.5 0 0 1 1.5 1.500v7a1.5 1.5 0 0 1-1.5 1.500h-11A1.5 1.5 0 0 1 5 19v-7a1.5 1.5 0 0 1 1.5-1.500z",
             "M8 10.500V8a4 4 0 0 1 8 0v2.5"],
    "bin": ["M4.5 7h15", "M9.5 7V4.500h5V7", "M6.5 7l.80 12a1.5 1.5 0 0 0 1.5 1.400h6.400a1.5 1.5 0 0 0 1.5-1.400l.80-12", "M10 11v5.5", "M14 11v5.5"],
    "sim": ["M7.5 3.500h6l4.5 4.500v11a1.5 1.5 0 0 1-1.5 1.500h-9A1.5 1.5 0 0 1 6 19V5a1.5 1.5 0 0 1 1.5-1.500z", "M9.5 12.500h5v5h-5z"],
    "update": ["M19.5 12a7.5 7.5 0 1 1-2.4-5.5", "M19.5 4v4h-4"],
    "star": ["M12 3.8l2.5 5.2l5.7.8l-4.1 4l1 5.7L12 16.8l-5.1 2.7l1-5.7l-4.1-4l5.7-.8z"],
    "archive": ["M4 5h16v4H4z", "M5.5 9v8.5A1.5 1.5 0 0 0 7 19h10a1.5 1.5 0 0 0 1.5-1.5V9", "M10 13h4"],
    "sliders": ["M5 7h9", "M18 7h1", "M16 5v4", "M5 17h1", "M10 17h9", "M8 15v4", "M5 12h14"],
    "bolt": ["M13.5 3L5.5 13.500h5.500L10 21l8.5-11h-5.500z"],
    "shield": ["M12 3l7.5 3v5.500c0 4.6-3 8-7.5 9.500c-4.5-1.5-7.5-4.9-7.5-9.500V6z", "M8.8 12l2.3 2.300l4.2-4.6"],
}


def icon(name, color, scale=1.0, x=0, y=0, width=1.9):
    paths = "".join(
        f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>'
        for d in ICONS[name]
    )
    return f'<g transform="translate({x} {y}) scale({scale})">{paths}</g>'


def list_icons():
    for name in ICONS:
        (OUT / f"ic-{name}.svg").write_text(
            f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">{icon(name, "#5B6EE8")}</svg>',
            encoding="utf-8",
        )


def feature(file, name, title, line1, line2):
    s = Svg(300, 250)
    s.add(GRADIENT)
    s.add('<rect width="300" height="250" rx="26" fill="url(#bg)"/><rect width="300" height="250" rx="26" fill="url(#glow)"/>')
    s.add('<rect x="1" y="1" width="298" height="248" rx="25" fill="none" stroke="#FFFFFF" stroke-opacity="0.16" stroke-width="2"/>')
    s.add('<circle cx="150" cy="66" r="34" fill="#FFFFFF" fill-opacity="0.12"/>')
    s.add('<circle cx="150" cy="66" r="33" fill="none" stroke="#FFFFFF" stroke-opacity="0.25" stroke-width="2"/>')
    s.add(icon(name, "#FFFFFF", 1.4167, 133, 49))
    s.text(150, 146, title, 29, "#F2F4FF", bold=True, anchor="middle")
    s.text(150, 188, line1, 19, "#C9D0FF", anchor="middle")
    s.text(150, 218, line2, 19, "#C9D0FF", anchor="middle")
    s.save(file)


def chip(file, name, label):
    s = Svg(218, 68)
    s.add(GRADIENT)
    s.add('<rect x="2" y="2" width="214" height="64" rx="32" fill="url(#bg)"/>')
    s.add('<rect x="3" y="3" width="212" height="62" rx="31" fill="none" stroke="#FFFFFF" stroke-opacity="0.22" stroke-width="2"/>')
    s.add(icon(name, "#C9D0FF", 1.0, 166, 22, 2.0))
    s.text(154, 41.9, label, 22, "#F2F4FF", bold=True)
    s.save(file)


def hero():
    s = Svg(1200, 400)
    s.add(GRADIENT)
    s.add('<rect width="1200" height="400" rx="36" fill="url(#bg)"/><rect width="1200" height="400" rx="36" fill="url(#glow)"/>')
    for cx, cy, r, o in ((160, 330, 190, 0.06), (1080, 60, 140, 0.07), (620, 430, 120, 0.05)):
        s.add(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="#FFFFFF" fill-opacity="{o}"/>')
    s.add(f'<g transform="translate(820 80) scale(1.0)">{SYMBOL.format(a="#FFFFFF", b="#C9D0FF")}</g>')
    s.text(760, 168, "برق", 124, "#FFFFFF", bold=True)
    s.text(760, 272, "رسائلك أسرع من البرق", 44, "#F2F4FF", bold=True)
    s.text(760, 328, "تطبيق رسائل SMS لأندرويد: سريع، ذكي، خاص", 28, "#C9D0FF")
    s.save("hero.svg")


def phone(s):
    s.add(f'<rect x="4" y="4" width="{s.w - 8}" height="{s.h - 8}" rx="42" fill="#14151C"/>')
    s.add(f'<rect x="12" y="12" width="{s.w - 24}" height="{s.h - 24}" rx="34" fill="{PAGE}"/>')
    s.add(f'<rect x="{s.w / 2 - 34}" y="24" width="68" height="14" rx="7" fill="#14151C"/>')


def island(s, y, h, x=26, w=None):
    w = w or s.w - 2 * x
    s.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="24" fill="{CARD}"/>')


def screen_inbox():
    s = Svg(320, 640)
    phone(s)
    s.text(288, 98, "الرسائل", 34, INK, bold=True)
    x = 288
    for label, w, on in (("الكل", 56, True), ("شخصي", 70, False), ("بنوك", 62, False), ("رموز", 60, False)):
        s.add(f'<rect x="{x - w}" y="118" width="{w}" height="32" rx="16" fill="{PRIMARY if on else CARD}"/>')
        s.text(x - w / 2, 139.5, label, 14, "#FFFFFF" if on else MUTE, bold=on, anchor="middle")
        x -= w + 8
    rows = (
        ("أ", "#4355B9", "أبو خالد", "الحمد لله، نلتقي بعد العصر", "10:42", 2),
        ("ب", "#1F7A4D", "بنك فلسطين", "تم إيداع 1,250 شيكل في حسابك", "09:15", 0),
        ("أ", "#77536D", "أم يوسف", "الله يرضى عليك يا ابني", "أمس", 1),
        ("ج", "#8A6100", "جوال", "رمز التحقق الخاص بك 482913", "أمس", 0),
        ("م", "#5B5D72", "محمود الأستاذ", "تمام، أرسلت لك الملف", "الخميس", 0),
    )
    island(s, 166, 72 * len(rows) + 8)
    for i, (letter, color, name, snippet, time, unread) in enumerate(rows):
        y = 170 + i * 72
        s.add(f'<circle cx="260" cy="{y + 36}" r="22" fill="{color}"/>')
        s.text(260, y + 43, letter, 19, "#FFFFFF", bold=True, anchor="middle")
        s.text(226, y + 31, name, 16, INK, bold=True)
        s.text(226, y + 54, snippet, 13, MUTE)
        s.text(40, y + 30, time, 11.5, PRIMARY if unread else MUTE, bold=bool(unread), rtl=False)
        if unread:
            s.add(f'<circle cx="50" cy="{y + 48}" r="10" fill="{PRIMARY}"/>')
            s.text(50, y + 52.5, str(unread), 11.5, "#FFFFFF", bold=True, anchor="middle", rtl=False)
        if i < len(rows) - 1:
            s.add(f'<rect x="40" y="{y + 71}" width="184" height="1" fill="#E3E1EC"/>')
    s.add(f'<rect x="60" y="560" width="200" height="52" rx="26" fill="{CARD}"/>')
    s.add(f'<rect x="206" y="566" width="48" height="40" rx="20" fill="#DEE0FF"/>')
    for name, cx, color in (("reply", 218, DEEP), ("star", 172, MUTE), ("archive", 126, MUTE), ("sliders", 80, MUTE)):
        s.add(icon(name, color, 0.9, cx, 575.2))
    s.save("screen-inbox.svg")


def bubble(s, y, lines, mine):
    width = 34 + max(len(line) for line in lines) * 7.6
    h = 20 + 22 * len(lines)
    x = 28 if mine else s.w - 28 - width
    s.add(f'<rect x="{x}" y="{y}" width="{width}" height="{h}" rx="20" fill="{PRIMARY if mine else "#E9E7F0"}"/>')
    for i, line in enumerate(lines):
        s.text(x + width - 16, y + 27 + i * 22, line, 14.5, "#FFFFFF" if mine else INK)
    return y + h


def screen_chat():
    s = Svg(320, 640)
    phone(s)
    s.add(f'<circle cx="264" cy="78" r="20" fill="{PRIMARY}"/>')
    s.text(264, 85, "أ", 18, "#FFFFFF", bold=True, anchor="middle")
    s.text(234, 84, "أبو خالد", 18, INK, bold=True)
    s.add('<rect x="118" y="112" width="84" height="24" rx="12" fill="#E3E1EC"/>')
    s.text(160, 129, "اليوم", 12, MUTE, anchor="middle")
    y = bubble(s, 150, ["السلام عليكم، كيف حالك؟"], False)
    y = bubble(s, y + 12, ["وعليكم السلام ورحمة الله", "الحمد لله بخير وعافية"], True)
    y = bubble(s, y + 12, ["يسعد مساك، ومبروك النجاح"], False)
    s.add(icon("reply", PRIMARY, 0.75, 274, 471))
    s.text(268, 484, "ردود مقترحة", 12.5, PRIMARY, bold=True)
    for i, label in enumerate(("يسعدك ويسعد مساك، الله يبارك فيك", "الله يبارك فيك، تسلم")):
        w = 36 + len(label) * 7.2
        yy = 496 + i * 0
        if i == 0:
            s.add(f'<rect x="{292 - w}" y="{yy}" width="{w}" height="34" rx="17" fill="#DEE0FF"/>')
            s.text(292 - 18, yy + 22, label, 13, "#00105C")
    s.add(f'<rect x="76" y="548" width="216" height="48" rx="24" fill="{CARD}"/>')
    s.text(272, 577.5, "اكتب رسالة", 14.5, MUTE)
    s.add(f'<circle cx="48" cy="572" r="22" fill="{PRIMARY}"/>')
    s.add(icon("send", "#FFFFFF", 0.85, 37.5, 562))
    s.save("screen-chat.svg")


def screen_reply():
    s = Svg(320, 640)
    phone(s)
    s.text(288, 98, "الرد التلقائي", 30, INK, bold=True)
    s.add('<rect x="26" y="120" width="268" height="72" rx="24" fill="#DEE0FF"/>')
    s.text(276, 152, "تشغيل الرد التلقائي", 15.5, "#00105C", bold=True)
    s.text(276, 174, "يرد عنك وأنت مشغول", 12.5, "#3A4374")
    s.add(f'<rect x="42" y="142" width="52" height="28" rx="14" fill="{PRIMARY}"/><circle cx="56" cy="156" r="10" fill="#FFFFFF"/>')
    s.text(286, 222, "نبرة الرد", 13.5, PRIMARY, bold=True)
    island(s, 232, 70)
    for i, (label, on) in enumerate((("فخمة", True), ("ودّية", False), ("مختصرة", False))):
        x = 202 - i * 82
        s.add(f'<rect x="{x}" y="248" width="78" height="38" rx="19" fill="{"#DEE0FF" if on else "none"}" stroke="#C7C5D0" stroke-width="{0 if on else 1.2}"/>')
        s.text(x + 39, 272, label, 13.5, "#00105C" if on else MUTE, bold=on, anchor="middle")
    s.text(286, 332, "جرّب المحرك", 13.5, PRIMARY, bold=True)
    island(s, 342, 226)
    s.text(276, 372, "رسالة واردة", 12, MUTE)
    s.add('<rect x="96" y="382" width="182" height="40" rx="20" fill="#E9E7F0"/>')
    s.text(262, 407, "اللهم آمين يسعد مساك", 14, INK)
    s.text(276, 452, "سيرد برق", 12, MUTE)
    s.add(f'<rect x="42" y="462" width="222" height="88" rx="20" fill="{PRIMARY}"/>')
    for i, line in enumerate(("اللهم آمين، ولك بمثله وزيادة", "أسعد الله مساءك بكل خير")):
        s.text(248, 496 + i * 26, line, 14, "#FFFFFF")
    s.save("screen-reply.svg")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    list_icons()
    hero()
    chip("chip-version.svg", "tag", f"الإصدار {VERSION}")
    chip("chip-lang.svg", "globe", "10 لغات")
    feature("feature-reply.svg", "reply", "رد يفهمك", "يفهم الرسالة ويصوغ", "الرد بأسلوبك ونبرتك")
    feature("feature-langs.svg", "globe", "10 لغات", "العربية والإنجليزية", "وثماني لغات أخرى")
    feature("feature-comfort.svg", "eye", "مريح للعين", "واجهة هادئة ومرتبة", "فاتحة وداكنة")
    screen_inbox()
    screen_chat()
    screen_reply()
