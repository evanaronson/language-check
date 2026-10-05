"""Writes the lingu·ize brand SVGs into brand/, the folder above this one.

    python3 brand/source/build.py
"""
import math
import os
import sys

sys.dont_write_bytecode = True           # leave no __pycache__ in brand/source
from kit import bbox_polys, path_d       # noqa: E402
from systems import params, word         # noqa: E402

OUT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
K = 0.1                                  # font units -> svg units for wordmarks
INK, PAPER = '#111111', '#FAFAF8'
COBALT = '#3340F0'                       # the one colour, on the hinge dot only
COBALT_DARK = '#7C84FF'                  # the same on dark surfaces (the app's night accent)
GRID = '#C9C9C6'
LABEL = '#6E6E6B'


# ------------------------------------------------------------------ parts
def split(items):
    """Place a Word's glyphs; returns (ink polys, hinge-dot polys)."""
    ink, dot = [], []
    for G, dx in items:
        for pl in G.polys:
            (dot if G.name == 'dot' else ink).append([(x + dx, y) for x, y in pl])
    return ink, dot


def word_parts(stem='lingu'):
    w, _ = word(stem)
    return split(w.items)


def icon_parts():
    """The icon is the word cropped to ·ize."""
    w, n = word()
    return split(w.items[n:n + 4])


def glyph_parts():
    """24dp glyph: the z with the hinge dot pulled in beside it (no i)."""
    w, n = word()
    dotG, _ = w.items[n]
    zG, zdx = w.items[n + 2]
    r = dotG.marks['dot'][2]
    # close the gap the i leaves: dot sits one dot-width left of the z
    dx_dot = (zdx + zG.x0) - 2 * r - 1.15 * 2 * r
    dot = [[(x + dx_dot, y) for x, y in pl] for pl in dotG.polys]
    z = [[(x + zdx, y) for x, y in pl] for pl in zG.polys]
    return z + dot


# ------------------------------------------------------------------ svg helpers
def svg_doc(vb, body, title):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}"><title>{title}</title>{body}</svg>\n'


def wm_box(polys_all, pad_u=40):
    x0, y0, x1, y1 = bbox_polys(polys_all)
    return x0 - pad_u, y0 - pad_u, x1 + pad_u, y1 + pad_u


def wordmark_svg(dark=False, color=False):
    """dark: paper ink on an ink background. color: the hinge dot in cobalt."""
    ink, hl = word_parts()
    x0, y0, x1, y1 = wm_box(ink + hl)
    ink_c = PAPER if dark else INK
    hl_c = (COBALT_DARK if dark else COBALT) if color else ink_c
    vb = f'{x0*K:.1f} {-y1*K:.1f} {(x1-x0)*K:.1f} {(y1-y0)*K:.1f}'
    body = ''
    if dark:
        body += f'<rect x="{x0*K:.1f}" y="{-y1*K:.1f}" width="{(x1-x0)*K:.1f}" height="{(y1-y0)*K:.1f}" fill="{INK}"/>'
    if hl_c == ink_c:
        body += f'<path fill="{ink_c}" d="{path_d(ink + hl, K)}"/>'
    else:
        body += f'<path fill="{ink_c}" d="{path_d(ink, K)}"/><path fill="{hl_c}" d="{path_d(hl, K)}"/>'
    return svg_doc(vb, body, 'linguize')


def verbs_svg(stems=('catalan', 'castilian'), gap=900):
    rows = []
    for i, st in enumerate(stems):
        ink, hl = word_parts(st)
        rows.append((ink, hl, i * gap))
    allp = [[(x, y - dy) for x, y in pl] for ink, hl, dy in rows for pl in ink + hl]
    x0, y0, x1, y1 = wm_box(allp)
    body = ''
    for ink, hl, dy in rows:
        sh = lambda P: [[(x, y - dy) for x, y in pl] for pl in P]
        body += f'<path fill="{INK}" d="{path_d(sh(ink), K)}"/><path fill="{INK}" d="{path_d(sh(hl), K)}"/>'
    vb = f'{x0*K:.1f} {-y1*K:.1f} {(x1-x0)*K:.1f} {(y1-y0)*K:.1f}'
    return svg_doc(vb, body, ', '.join(s + 'ize' for s in stems))


# ------------------------------------------------------------------ icon
ICON_MAXW, ICON_R, ICON_LIFT = 46, 27.0, -1.0    # 108 canvas: max width, safe radius, optical lift


def icon_paths():
    """(ink path, dot path) of the icon crop, fitted to the 108 canvas."""
    ink, hl = icon_parts()
    polys = ink + hl
    x0, y0, x1, y1 = bbox_polys(polys)
    mx, my = (x0 + x1) / 2, (y0 + y1) / 2
    far = max(math.hypot(q[0] - mx, q[1] - my) for pl in polys for q in pl)
    s = min(ICON_R / far, ICON_MAXW / (x1 - x0), ICON_MAXW / (y1 - y0))
    tx = 54 - mx * s
    ty = 54 + my * s + ICON_LIFT
    return path_d(ink, s, tx, ty), path_d(hl, s, tx, ty)


def icon_svg(color=False):
    """Launcher icon: paper on ink, or with color ink on paper with a cobalt dot."""
    d_ink, d_hl = icon_paths()
    bg, ink, det = (PAPER, INK, COBALT) if color else (INK, PAPER, PAPER)
    body = f'<rect width="108" height="108" fill="{bg}"/><path fill="{ink}" d="{d_ink}"/><path fill="{det}" d="{d_hl}"/>'
    return svg_doc('0 0 108 108', body, 'linguize icon')


def mono_svg():
    d_ink, d_hl = icon_paths()
    return svg_doc('0 0 108 108', f'<path fill="#000" d="{d_ink}{d_hl}"/>', 'linguize monochrome')


def glyph_svg():
    polys = glyph_parts()
    x0, y0, x1, y1 = bbox_polys(polys)
    mx, my = (x0 + x1) / 2, (y0 + y1) / 2
    s = min(20 / (x1 - x0), 20 / (y1 - y0))
    d = path_d(polys, s, 12 - mx * s, 12 + my * s, nd=3)
    return svg_doc('0 0 24 24', f'<path fill="#000" d="{d}"/>', 'linguize glyph')


# ------------------------------------------------------------------ construction
NOTE = 'x-height 500 · stem 100 · bars 86 · overshoot 10 · dots ⌀113 · hinge at ½ x-height'


def construction_svg():
    """wordmark on its metrics, the icon's crop boxed, and the icon it becomes."""
    ink, hl = word_parts()
    iink, ihl = icon_parts()
    p = params()
    k = K
    x0, y0, x1, y1 = bbox_polys(ink + hl)
    cx0, cy0, cx1, cy1 = bbox_polys(iink + ihl)
    pad = 45
    cx0, cy0, cx1, cy1 = cx0 - pad, cy0 - pad, cx1 + pad, cy1 + pad
    fs = 105                                  # label size, font units
    L = x0 - 120
    R = x1 + 120
    top_y = max(y1, p.ASC) + 230              # font units (y up)
    # icon below the crop box
    isz = max(cx1 - cx0, 900)
    icx = min((cx0 + cx1) / 2, R - isz / 2)
    arrow_top = min(y0, p.DESC) - 70
    icon_top = arrow_top - 230
    icon_bot = icon_top - isz
    note_y = icon_bot - 230
    bot_y = note_y - 140
    W, H = R - L, top_y - bot_y
    Y = lambda y: -y * k
    body = f'<rect x="{L*k:.1f}" y="{Y(top_y):.1f}" width="{W*k:.1f}" height="{H*k:.1f}" fill="{PAPER}"/>'
    for yv in (p.ASC, p.X, 0, p.DESC):
        body += f'<line x1="{(x0-20)*k:.1f}" x2="{(x1+20)*k:.1f}" y1="{Y(yv):.1f}" y2="{Y(yv):.1f}" stroke="{GRID}" stroke-width="0.8"/>'
    body += f'<path fill="{INK}" d="{path_d(ink + hl, k)}"/>'
    body += (f'<rect x="{cx0*k:.1f}" y="{Y(cy1):.1f}" width="{(cx1-cx0)*k:.1f}" height="{(cy1-cy0)*k:.1f}" '
             f'fill="none" stroke="{INK}" stroke-width="2.2" stroke-dasharray="7 5" rx="5"/>')
    body += (f'<text x="{(cx0+10)*k:.1f}" y="{Y(cy1)-6:.1f}" font-family="sans-serif" font-size="{fs*k:.1f}" '
             f'font-weight="700" fill="{INK}">icon = this crop</text>')
    # arrow from crop box down to icon
    ax = icx * k
    body += f'<line x1="{ax:.1f}" x2="{ax:.1f}" y1="{Y(cy0)+3:.1f}" y2="{Y(icon_top)-9:.1f}" stroke="{INK}" stroke-width="2"/>'
    body += f'<path d="M{ax:.1f} {Y(icon_top)-2:.1f}l-6 -10h12z" fill="{INK}"/>'
    d_ink, d_hl = icon_paths()
    sc = isz * k / 108
    gx, gy = (icx - isz / 2) * k, Y(icon_top)
    body += (f'<g transform="translate({gx:.2f} {gy:.2f}) scale({sc:.4f})">'
             f'<rect width="108" height="108" rx="26" fill="{INK}"/>'
             f'<circle cx="54" cy="54" r="33" fill="none" stroke="#6a6a68" stroke-width="0.6" stroke-dasharray="1.6 1.6"/>'
             f'<path fill="{PAPER}" d="{d_ink}{d_hl}"/></g>')
    body += (f'<text x="{(icx - isz/2 - 40)*k:.1f}" y="{Y(icon_top - isz/2)+4:.1f}" font-family="sans-serif" '
             f'font-size="{fs*0.9*k:.1f}" fill="{LABEL}" text-anchor="end">same paths,</text>'
             f'<text x="{(icx - isz/2 - 40)*k:.1f}" y="{Y(icon_top - isz/2)+16:.1f}" font-family="sans-serif" '
             f'font-size="{fs*0.9*k:.1f}" fill="{LABEL}" text-anchor="end">scaled into the 66 safe circle</text>')
    body += (f'<text x="{x0*k:.1f}" y="{Y(note_y):.1f}" font-family="sans-serif" font-size="{fs*0.85*k:.1f}" '
             f'fill="{LABEL}">{NOTE}</text>')
    vb = f'{L*k:.1f} {Y(top_y):.1f} {W*k:.1f} {H*k:.1f}'
    return svg_doc(vb, body, 'construction')


# ------------------------------------------------------------------ main
def main():
    files = {
        'wordmark.svg': wordmark_svg(),
        'wordmark-colour.svg': wordmark_svg(color=True),
        'wordmark-dark.svg': wordmark_svg(dark=True),
        'icon.svg': icon_svg(),
        'icon-colour.svg': icon_svg(color=True),
        'mono.svg': mono_svg(),
        'glyph.svg': glyph_svg(),
        'verbs.svg': verbs_svg(),
        'construction.svg': construction_svg(),
    }
    for name, svg in files.items():
        path = os.path.join(OUT, name)
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(svg)
        print(os.path.relpath(path))


if __name__ == '__main__':
    main()
