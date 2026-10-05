"""Build every r3 deliverable from the kit.  python3 build.py"""
import math, os, json
from kit import *
from systems import *

OUT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
K = 0.1                                  # font units -> svg units for wordmarks
INK, PAPER = '#111111', '#FAFAF8'
GRID = '#C9C9C6'
LABEL = '#6E6E6B'

# one colour per system (+ a lighter tint of the same hue for dark surfaces)
COLOR = {
    'A': dict(name='Cobalt', hex='#3340F0', dark='#8C95FF',
              use='the hinge dot only',
              why='One cold, exact point of cobalt, and only on the hinge dot, so the single coloured mark sits exactly where the word changes.'),
    'B': dict(name='Marker yellow', hex='#FFDD2E', dark='#FFDD2E',
              use='the icon field (and in-app fix highlights)',
              why='The suffix is written with a chisel-tip marker, so the one colour is the highlighter the app already uses to mark your fixes.'),
    'C': dict(name='Vermilion', hex='#EE4A2B', dark='#FF6E50',
              use='the stress accent only',
              why='Only the accent is coloured: the stress mark is the single detail that changes, like the accent you forgot and the app puts back.'),
}

META = {
    'A': dict(title='Hinge', tag='lingu·ize', idea='The Catalan punt volat (l·l) used as a hinge: the raised dot marks where -ize snaps onto any word.'),
    'B': dict(title='Voice', tag='lingu + ize', idea='Typed word, written suffix: upright monoline lingu, then -ize in a chisel-pen italic drawn on the same skeletons.'),
    'C': dict(title='Stress', tag='linguíze', idea='The suffix is stressed the Spanish/Catalan way: an acute accent on its i, plus a jump from light to black weight.'),
}


# ------------------------------------------------------------------ parts
def word_parts(key, stem='lingu'):
    """[(polys, role)] role 'ink' or 'hl' (the one coloured detail)."""
    w, n = SYSTEMS[key](stem)
    ink, hl = [], []
    for idx, (G, dx) in enumerate(w.items):
        for j, pl in enumerate(G.polys):
            q = [(x + dx, y) for x, y in pl]
            if key == 'A' and G.name == 'dot':
                hl.append(q)
            elif key == 'C' and G.marks.get('acc_poly') == j:
                hl.append(q)
            else:
                ink.append(q)
    return w, n, ink, hl


def icon_parts(key):
    w, n, _, _ = word_parts(key)
    rng = {'A': (n, n + 4), 'B': (n, n + 3), 'C': (n, n + 3)}[key]
    ink, hl = [], []
    for G, dx in w.items[rng[0]:rng[1]]:
        for j, pl in enumerate(G.polys):
            q = [(x + dx, y) for x, y in pl]
            if (key == 'A' and G.name == 'dot') or (key == 'C' and G.marks.get('acc_poly') == j):
                hl.append(q)
            else:
                ink.append(q)
    return w, rng, ink, hl


def glyph_parts(key):
    """24dp glyph: a shorter crop/reduction of the icon, 2-3 shapes."""
    w, n, _, _ = word_parts(key)
    items = w.items
    if key == 'A':
        dotG, ddx = items[n]
        zG, zdx = items[n + 2]
        r = dotG.marks['dot'][2]
        # close the gap the i leaves: dot sits one dot-width left of the z
        dx_dot = (zdx + zG.x0) - 2 * r - 1.15 * 2 * r
        dot = [[(x + dx_dot, y) for x, y in pl] for pl in dotG.polys]
        z = [[(x + zdx, y) for x, y in pl] for pl in zG.polys]
        return z + dot
    if key == 'B':
        # same z, drawn with a slightly fatter nib (optical size for 24dp)
        pi = params_Bi(params_B()).copy(pen_B=0.36)
        zG = g_z_pen(pi)
        return [list(pl) for pl in zG.polys]
    if key == 'C':
        out = []
        for G, dx in items[n:n + 2]:
            out += [[(x + dx, y) for x, y in pl] for pl in G.polys]
        return out


# ------------------------------------------------------------------ svg helpers
def svg_doc(vb, body, w=None, h=None, title=None):
    size = ''
    if w:
        size = f' width="{w:.0f}" height="{h:.0f}"'
    t = f'<title>{title}</title>' if title else ''
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}"{size}>{t}{body}</svg>\n'


def wm_box(polys_all, pad_u=40):
    x0, y0, x1, y1 = bbox_polys(polys_all)
    return x0 - pad_u, y0 - pad_u, x1 + pad_u, y1 + pad_u


def wordmark_svg(key, stem='lingu', dark=False, color=False, bg=None):
    w, n, ink, hl = word_parts(key, stem)
    x0, y0, x1, y1 = wm_box(ink + hl)
    ink_c = PAPER if dark else INK
    hl_c = ink_c
    if color and key in ('A', 'C'):
        hl_c = COLOR[key]['dark' if dark else 'hex']
    vb = f'{x0*K:.1f} {-y1*K:.1f} {(x1-x0)*K:.1f} {(y1-y0)*K:.1f}'
    body = ''
    if bg:
        body += f'<rect x="{x0*K:.1f}" y="{-y1*K:.1f}" width="{(x1-x0)*K:.1f}" height="{(y1-y0)*K:.1f}" fill="{bg}"/>'
    if hl_c == ink_c:
        body += f'<path fill="{ink_c}" d="{path_d(ink + hl, K)}"/>'
    else:
        body += f'<path fill="{ink_c}" d="{path_d(ink, K)}"/><path fill="{hl_c}" d="{path_d(hl, K)}"/>'
    return svg_doc(vb, body, title='linguize')


def verbs_svg(key, dark=False, color=False, stems=('catalan', 'castilian'), gap=900):
    rows = []
    for i, st in enumerate(stems):
        w, n, ink, hl = word_parts(key, st)
        rows.append((ink, hl, i * gap))
    allp = [[(x, y - dy) for x, y in pl] for ink, hl, dy in rows for pl in ink + hl]
    x0, y0, x1, y1 = wm_box(allp)
    ink_c = PAPER if dark else INK
    hl_c = COLOR[key]['dark' if dark else 'hex'] if (color and key in ('A', 'C')) else ink_c
    body = ''
    for ink, hl, dy in rows:
        sh = lambda P: [[(x, y - dy) for x, y in pl] for pl in P]
        body += f'<path fill="{ink_c}" d="{path_d(sh(ink), K)}"/><path fill="{hl_c}" d="{path_d(sh(hl), K)}"/>'
    vb = f'{x0*K:.1f} {-y1*K:.1f} {(x1-x0)*K:.1f} {(y1-y0)*K:.1f}'
    return svg_doc(vb, body, title='catalanize, castilianize')


# ------------------------------------------------------------------ icon
ICON_FIT = {'A': dict(maxw=46, R=27.0, lift=-1.0), 'B': dict(maxw=46, R=27.0, lift=-0.5), 'C': dict(maxw=43, R=27.0, lift=-1.0)}


def icon_transform(key, polys):
    x0, y0, x1, y1 = bbox_polys(polys)
    mx, my = (x0 + x1) / 2, (y0 + y1) / 2
    far = max(math.hypot(q[0] - mx, q[1] - my) for pl in polys for q in pl)
    f = ICON_FIT[key]
    s = min(f['R'] / far, f['maxw'] / (x1 - x0), f['maxw'] / (y1 - y0))
    tx = 54 - mx * s
    ty = 54 + my * s + f['lift']
    return s, tx, ty


def icon_paths(key):
    w, rng, ink, hl = icon_parts(key)
    s, tx, ty = icon_transform(key, ink + hl)
    return path_d(ink, s, tx, ty, eps=0.12), path_d(hl, s, tx, ty, eps=0.12), (s, tx, ty)


def icon_colors(key, color=False):
    """(bg, ink, detail) for the launcher icon."""
    if not color:
        return INK, PAPER, PAPER
    c = COLOR[key]
    if key == 'A':
        return PAPER, INK, c['hex']
    if key == 'B':
        return c['hex'], INK, INK
    return INK, PAPER, c['hex']


def icon_svg(key, color=False):
    d_ink, d_hl, _ = icon_paths(key)
    bg, ink, det = icon_colors(key, color)
    body = f'<rect width="108" height="108" fill="{bg}"/><path fill="{ink}" d="{d_ink}"/>'
    if d_hl:
        body += f'<path fill="{det}" d="{d_hl}"/>'
    return svg_doc('0 0 108 108', body, title='linguize icon')


def mono_svg(key):
    d_ink, d_hl, _ = icon_paths(key)
    return svg_doc('0 0 108 108', f'<path fill="#000" d="{d_ink}{d_hl}"/>', title='linguize monochrome')


def glyph_svg(key, fill='#000'):
    polys = glyph_parts(key)
    x0, y0, x1, y1 = bbox_polys(polys)
    mx, my = (x0 + x1) / 2, (y0 + y1) / 2
    s = min(20 / (x1 - x0), 20 / (y1 - y0))
    d = path_d(polys, s, 12 - mx * s, 12 + my * s, eps=0.12, nd=3)
    return svg_doc('0 0 24 24', f'<path fill="{fill}" d="{d}"/>', title='linguize glyph'), s


# ------------------------------------------------------------------ construction
NOTES = {
    'A': 'x-height 500 · stem 100 · bars 86 · overshoot 10 · dots ⌀113 · hinge at ½ x-height',
    'B': 'upright: nib 92×79 · italic: chisel nib 57×25 at 25°, slant 11° · same x-height',
    'C': 'lingu: stem 72 · íze: stem 126 · accent 60°, 0.78 of the stem · same x-height',
}


def construction_svg(key):
    """wordmark on its metrics, the icon's crop boxed, and the icon it becomes."""
    w, n, ink, hl = word_parts(key)
    _, rng, iink, ihl = icon_parts(key)
    p = Params()
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
    for yv, lab in ((p.ASC, 'asc'), (p.X, 'x'), (0, 'base'), (p.DESC, 'desc')):
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
    d_ink, d_hl, _ = icon_paths(key)
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
             f'fill="{LABEL}">{NOTES[key]}</text>')
    vb = f'{L*k:.1f} {Y(top_y):.1f} {W*k:.1f} {H*k:.1f}'
    return svg_doc(vb, body, title='construction')


# ------------------------------------------------------------------ main
def write(path, txt):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        f.write(txt)


def build_all():
    for key in 'ABC':
        d = os.path.join(OUT, key)
        write(os.path.join(d, 'wordmark.svg'), wordmark_svg(key))
        write(os.path.join(d, 'wordmark-dark.svg'), wordmark_svg(key, dark=True, bg=INK))
        write(os.path.join(d, 'verbs.svg'), verbs_svg(key))
        write(os.path.join(d, 'icon.svg'), icon_svg(key))
        write(os.path.join(d, 'mono.svg'), mono_svg(key))
        g, s = glyph_svg(key)
        write(os.path.join(d, 'glyph.svg'), g)
        write(os.path.join(d, 'construction.svg'), construction_svg(key))
        # the one-colour variants
        write(os.path.join(d, 'wordmark-colour.svg'), wordmark_svg(key, color=True))
        write(os.path.join(d, 'icon-colour.svg'), icon_svg(key, color=True))
        print(key, 'glyph scale', round(s, 4))


if __name__ == '__main__':
    build_all()
