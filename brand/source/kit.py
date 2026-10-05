"""Parametric geometric lettering.

Every letter is a centre-line skeleton swept with an axis-aligned elliptical
nib (a = W/2 across, b = H/2 up), so vertical strokes are W thick, horizontal
strokes H thick and curves interpolate exactly between them. Bowls thin where
they meet stems through a width profile; terminals are trimmed by exact cut
lines; overlapping contours are unioned by the nonzero fill rule.
Coordinates are font units, y up, baseline 0, x-height X.
"""
import math

# ------------------------------------------------------------------ geometry

def rad(d):
    return d * math.pi / 180.0


def lerp(p, q, t):
    return (p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t)


def dist(p, q):
    return math.hypot(q[0] - p[0], q[1] - p[1])


def line(p0, p1, step=4.0):
    n = max(1, int(math.ceil(dist(p0, p1) / step)))
    return [lerp(p0, p1, i / n) for i in range(n + 1)]


def arc(cx, cy, rx, ry, d0, d1, step=0.5):
    n = max(2, int(math.ceil(abs(d1 - d0) / step)))
    out = []
    for i in range(n + 1):
        t = rad(d0 + (d1 - d0) * i / n)
        out.append((cx + rx * math.cos(t), cy + ry * math.sin(t)))
    return out


def join(*segs):
    out = []
    for s in segs:
        for p in s:
            if out and dist(out[-1], p) < 1e-6:
                continue
            out.append(p)
    return out


def ell_pt(cx, cy, rx, ry, deg):
    t = rad(deg)
    return (cx + rx * math.cos(t), cy + ry * math.sin(t))


def ell_tan(rx, ry, deg):
    t = rad(deg)
    return (-rx * math.sin(t), ry * math.cos(t))


def support(nx, ny, a, b):
    """Point on ellipse (a,b) whose outward normal is (nx,ny)."""
    d = math.sqrt((a * nx) ** 2 + (b * ny) ** 2)
    return (a * a * nx / d, b * b * ny / d)


def area(poly):
    s = 0.0
    n = len(poly)
    for i in range(n):
        x0, y0 = poly[i]
        x1, y1 = poly[(i + 1) % n]
        s += x0 * y1 - x1 * y0
    return s / 2


def orient(poly, sign):
    return poly if (area(poly) > 0) == (sign > 0) else poly[::-1]


def side(p, q, d):
    return d[0] * (p[1] - q[1]) - d[1] * (p[0] - q[0])


def trim(poly, q, d, at_end=True):
    """Cut an offset polyline where it crosses the line (q, d); drop the tail."""
    pts = poly if at_end else poly[::-1]
    s_end = side(pts[-1], q, d)
    for i in range(len(pts) - 1, 0, -1):
        s0 = side(pts[i - 1], q, d)
        if s0 == 0 or (s0 > 0) != (s_end > 0):
            s1 = side(pts[i], q, d)
            t = s0 / (s0 - s1)
            res = pts[:i] + [lerp(pts[i - 1], pts[i], t)]
            return res if at_end else res[::-1]
    raise ValueError('trim: no crossing')


def normal_cut(pt, tangent, rot=0.0):
    """Cut line through pt perpendicular to tangent (optionally rotated)."""
    tx, ty = tangent
    l = math.hypot(tx, ty)
    nx, ny = -ty / l, tx / l
    if rot:
        c, s = math.cos(rad(rot)), math.sin(rad(rot))
        nx, ny = nx * c - ny * s, nx * s + ny * c
    return (pt, (nx, ny))


def rdp(pts, eps):
    if len(pts) < 3:
        return pts
    a, b = pts[0], pts[-1]
    dx, dy = b[0] - a[0], b[1] - a[1]
    L = math.hypot(dx, dy)
    best, idx = -1, 0
    for i in range(1, len(pts) - 1):
        p = pts[i]
        if L < 1e-9:
            dd = dist(p, a)
        else:
            dd = abs(dx * (a[1] - p[1]) - dy * (a[0] - p[0])) / L
        if dd > best:
            best, idx = dd, i
    if best > eps:
        return rdp(pts[:idx + 1], eps)[:-1] + rdp(pts[idx:], eps)
    return [a, b]


def simplify_closed(poly, eps=0.12):
    if len(poly) < 8:
        return poly
    # split at the point furthest from the first to keep corners
    far = max(range(len(poly)), key=lambda i: dist(poly[0], poly[i]))
    a = rdp(poly[:far + 1], eps)
    b = rdp(poly[far:] + [poly[0]], eps)
    return a[:-1] + b[:-1]


# ------------------------------------------------------------------ stroking

def offsets(pts, a, b, prof=None, closed=False):
    """Left and right edges of the nib swept along pts; prof scales the nib per point."""
    n = len(pts)
    s = [0.0]
    for i in range(1, n):
        s.append(s[-1] + dist(pts[i - 1], pts[i]))
    L = s[-1] or 1.0
    left, right = [], []
    for i in range(n):
        if closed:
            d = (pts[(i + 1) % n][0] - pts[i - 1][0], pts[(i + 1) % n][1] - pts[i - 1][1])
        elif i == 0:
            d = (pts[1][0] - pts[0][0], pts[1][1] - pts[0][1])
        elif i == n - 1:
            d = (pts[-1][0] - pts[-2][0], pts[-1][1] - pts[-2][1])
        else:
            d = (pts[i + 1][0] - pts[i - 1][0], pts[i + 1][1] - pts[i - 1][1])
        l = math.hypot(*d)
        tx, ty = d[0] / l, d[1] / l
        nx, ny = -ty, tx
        if prof is None:
            f = 1.0
        elif callable(prof):
            f = prof(s[i] / L)
        else:
            f = prof[i]
        sx, sy = support(nx, ny, a * f, b * f)
        left.append((pts[i][0] + sx, pts[i][1] + sy))
        right.append((pts[i][0] - sx, pts[i][1] - sy))
    return left, right


# ------------------------------------------------------------------ params

class Params:
    """Metrics and weight. Any keyword overrides a default or sets one of the
    optional tuning knobs the letters read with getattr."""

    def __init__(self, **kw):
        self.X = 500.0          # x-height
        self.O = 10.0           # overshoot of rounds
        self.ASC = 690.0        # ascender (l)
        self.DESC = -205.0      # descender (g)
        self.W = 86.0           # vertical stem
        self.contrast = 0.86    # horizontal / vertical
        self.wide = 1.0         # width multiplier
        self.S = 52.0           # straight sidebearing
        self.dotk = 1.13        # dot diameter / stem
        self.dot_top = None     # top of i dot (default ASC + 4)
        self.__dict__.update(kw)
        self.H = self.W * self.contrast
        self.a = self.W / 2
        self.b = self.H / 2
        if self.dot_top is None:
            self.dot_top = self.ASC + 4
        # base widths (outer ink), scale with weight so counters stay open
        k = self.wide
        self.wn = (0.62 * self.X + 1.25 * self.W) * k
        self.wo = (0.86 * self.X + 0.42 * self.W) * k
        self.wz = (0.56 * self.X + 0.78 * self.W) * k
        self.wa = (0.80 * self.X + 0.95 * self.W) * k
        self.we = (0.86 * self.X + 0.38 * self.W) * k
        self.wc = (0.84 * self.X + 0.36 * self.W) * k
        self.ws = (0.60 * self.X + 0.70 * self.W) * k


# ------------------------------------------------------------------ glyph

class Glyph:
    def __init__(self, p, name):
        self.p = p
        self.name = name
        self.polys = []          # oriented polygons (+ fill, - hole)
        self.x0 = 0.0            # body extents, for spacing
        self.x1 = 0.0
        self.lsb = p.S
        self.rsb = p.S
        self.marks = {}          # named anchors (the hinge dot's centre and radius)

    def fill(self, poly):
        self.polys.append(orient(poly, +1))

    def hole(self, poly):
        self.polys.append(orient(poly, -1))

    def stroke(self, pts, prof=None, a=None, b=None, cut0=None, cut1=None):
        """Open stroke. cut0/cut1: (point, dir) cut lines at the start/end."""
        p = self.p
        a = p.a if a is None else a
        b = p.b if b is None else b
        L, R = offsets(pts, a, b, prof)
        if cut1 is not None:
            q, d = cut1
            L, R = trim(L, q, d, True), trim(R, q, d, True)
        if cut0 is not None:
            q, d = cut0
            L, R = trim(L, q, d, False), trim(R, q, d, False)
        self.fill(L + R[::-1])

    def ring(self, pts, prof=None):
        """Closed stroke: the outer edge filled, the inner edge a hole."""
        L, R = offsets(pts, self.p.a, self.p.b, prof, closed=True)
        if abs(area(L)) > abs(area(R)):
            L, R = R, L
        self.fill(R)
        self.hole(L)

    def dot(self, cx, cy, r, n=96):
        self.fill([(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)])

    def transform(self, fn):
        self.polys = [[fn(q) for q in pl] for pl in self.polys]


# ------------------------------------------------------------------ letters
# every builder returns a Glyph whose body spans x0..x1

def g_l(p):
    G = Glyph(p, 'l')
    G.stroke(line((p.a, 0), (p.a, p.ASC)))
    G.x0, G.x1 = 0, p.W
    return G


def g_i(p):
    G = Glyph(p, 'i')
    G.stroke(line((p.a, 0), (p.a, p.X)))
    r = p.W * p.dotk / 2
    G.dot(p.a, p.dot_top - r, r)
    G.x0, G.x1 = 0, p.W
    return G


def g_n(p, w=None, name='n'):
    """stem + arch. The arch is the region between two ellipses that share
    their centre height yc: the outer one starts inside the stem (xo_l), the
    inner one is tangent to the stem's right edge, so the arch thins as it
    leaves the stem and every contour is a pure ellipse."""
    w = w or p.wn
    W, H, X, O = p.W, p.H, p.X, p.O
    G = Glyph(p, name)
    G.fill([(0, 0), (W, 0), (W, X), (0, X)])
    xo_l = getattr(p, 'arch_in', 0.42) * W
    xi_l = W - getattr(p, 'arch_bite', 0.0) * W
    rxo = (w - xo_l) / 2
    cxo = xo_l + rxo
    ryo = rxo * getattr(p, 'arch_k', 1.0)
    yc = X + O - ryo
    rxi = (w - W - xi_l) / 2
    cxi = xi_l + rxi
    ryi = ryo - H * getattr(p, 'arch_top', 1.0)
    outer = arc(cxo, yc, rxo, ryo, 180, 0)
    inner = arc(cxi, yc, rxi, ryi, 0, 180)
    G.fill(outer + [(w, 0), (w - W, 0)] + inner)
    G.x0, G.x1 = 0, w
    return G


def g_u(p, w=None):
    """n turned half a turn."""
    w = w or p.wn
    G = g_n(p, w, 'u')
    G.transform(lambda q: (w - q[0], p.X - q[1]))
    return G


def g_o(p, w=None):
    w = w or p.wo
    G = Glyph(p, 'o')
    rx, ry = (w - p.W) / 2, p.X / 2 + p.O - p.b
    G.ring(arc(w / 2, p.X / 2, rx, ry, 0, 360)[:-1])
    G.x0, G.x1 = 0, w
    return G


def _bowl(G, p, w):
    """ring for a/g: right side buried in the stem at [w-W, w]."""
    inset = getattr(p, 'bowl_inset', 0.30) * p.W
    wb = w - inset
    rx = (wb - p.W) / 2
    cx = p.a + rx
    ry = p.X / 2 + p.O - p.b
    pts = arc(cx, p.X / 2, rx, ry, -180, 180)[:-1]
    sig = getattr(p, 'bowl_sig', 52.0)
    f0 = getattr(p, 'bowl_f', 0.66)
    prof = []
    for i in range(len(pts)):
        th = -180 + 360 * i / len(pts)
        prof.append(1 - (1 - f0) * math.exp(-(th / sig) ** 2))
    G.ring(pts, prof)
    return cx, rx, ry


def g_a(p, w=None):
    w = w or p.wa
    G = Glyph(p, 'a')
    _bowl(G, p, w)
    G.stroke(line((w - p.a, 0), (w - p.a, p.X)))
    G.x0, G.x1 = 0, w
    return G


def g_g(p, w=None):
    w = w or p.wa
    G = Glyph(p, 'g')
    _, rxb, _ = _bowl(G, p, w)
    a, b = p.a, p.b
    rxh = getattr(p, 'g_hook_rx', 0.92) * rxb
    ryh = getattr(p, 'g_hook_ry', 0.58) * (p.X / 2)
    yh = p.DESC - p.O + b + ryh
    cx = w - a - rxh
    end = getattr(p, 'g_end', -158.0)
    pts = join(line((w - a, p.X), (w - a, yh)), arc(cx, yh, rxh, ryh, 0, end - 8))
    cut = normal_cut(ell_pt(cx, yh, rxh, ryh, end), ell_tan(rxh, ryh, end), getattr(p, 'term_rot', 0.0))
    G.stroke(pts, cut1=cut)
    G.x0, G.x1 = 0, w
    return G


def g_e(p, w=None):
    w = w or p.we
    G = Glyph(p, 'e')
    b, X = p.b, p.X
    rx, ry = (w - p.W) / 2, X / 2 + p.O - b
    cx, cy = w / 2, X / 2
    bar_y = getattr(p, 'e_bar', 0.515) * X          # bar centre
    hb = getattr(p, 'e_barw', 0.84) * p.W / 2       # half bar thickness
    yb = bar_y - hb                                  # bar bottom = cut
    th0 = math.degrees(math.asin((yb - cy) / ry)) - 8
    end = 360 + getattr(p, 'e_end', -36.0)
    pts = arc(cx, cy, rx, ry, th0, end + 10)
    cut = normal_cut(ell_pt(cx, cy, rx, ry, end), ell_tan(rx, ry, end), getattr(p, 'term_rot', 0.0))
    G.stroke(pts, cut0=((0, yb), (-1, 0)), cut1=cut)
    # crossbar: from the left stroke to the right stroke's centre line
    G.stroke(line((cx - rx, bar_y), (cx + rx, bar_y)), b=hb)
    G.x0, G.x1 = 0, w
    return G


def g_c(p, w=None):
    w = w or p.wc
    G = Glyph(p, 'c')
    rx, ry = (w - p.W) / 2, p.X / 2 + p.O - p.b
    cx, cy = w / 2, p.X / 2
    t0 = getattr(p, 'c_open', 40.0)
    rot = getattr(p, 'term_rot', 0.0)
    pts = arc(cx, cy, rx, ry, t0 - 10, 360 - t0 + 10)
    G.stroke(pts,
             cut0=normal_cut(ell_pt(cx, cy, rx, ry, t0), ell_tan(rx, ry, t0), -rot),
             cut1=normal_cut(ell_pt(cx, cy, rx, ry, 360 - t0), ell_tan(rx, ry, 360 - t0), rot))
    G.x0, G.x1 = 0, w
    return G


def g_z(p, w=None):
    """one polygon: bars H thick, the diagonal as thick as the nib is across
    it, acute corners blunted by a short vertical cut k."""
    w = w or p.wz
    G = Glyph(p, 'z')
    X = p.X
    H = p.H * getattr(p, 'z_bar', 1.0)
    top_inset = getattr(p, 'z_top', 0.035)
    Td = getattr(p, 'z_diag', 0.93) * p.W
    wt = w * (1 - top_inset)       # top bar a touch shorter (optical)
    tl = w * top_inset * 0.5
    k = getattr(p, 'z_blunt', 0.30) * H
    phi = math.atan2(X - 2 * k, wt)
    for _ in range(8):
        # nib thickness in the diagonal's normal direction
        nib = 2 * math.sqrt((p.a * math.sin(phi)) ** 2 + (p.b * math.cos(phi)) ** 2)
        T = Td / p.W * nib
        R = math.hypot(wt, X - 2 * k)
        phi = math.atan2(X - 2 * k, wt) + math.asin(T / R)
    tn = math.tan(phi)
    xb = wt - (X - k - H) / tn
    xa = (X - H - k) / tn
    G.fill([(0, 0), (w, 0), (w, H), (xb, H), (wt, X - k), (wt, X), (tl, X), (tl, X - H), (xa, X - H), (0, k)])
    G.x0, G.x1 = 0, w
    return G


def g_t(p):
    G = Glyph(p, 't')
    bl = getattr(p, 't_left', 0.80) * p.W
    br = getattr(p, 't_right', 1.05) * p.W
    top = p.X + getattr(p, 't_top', 0.52) * (p.ASC - p.X)
    G.stroke(line((bl + p.a, 0), (bl + p.a, top)))
    G.stroke(line((0, p.X - p.b), (bl + p.W + br, p.X - p.b)))
    G.x0, G.x1 = 0, bl + p.W + br
    return G


def g_s(p, w=None):
    """two stacked circles joined by their inner common tangent, x-stretched."""
    w = w or p.ws
    G = Glyph(p, 's')
    b, X, O = p.b, p.X, p.O
    k = getattr(p, 's_ratio', 1.07)            # lower bowl / upper bowl
    span = X + 2 * O - 2 * b                   # skeleton height
    ru = span / 2 / (1 + k) * getattr(p, 's_r', 0.93)
    rl = ru * k
    yu = X + O - b - ru
    yl = -O + b + rl
    D = yu - yl
    th = 180 + math.degrees(math.asin((ru + rl) / D))   # tangent pt, upper circle
    ph = th - 180                                       # tangent pt, lower circle
    t_up = getattr(p, 's_t1', 32.0)
    t_lo = getattr(p, 's_t2', -148.0)
    Tu = ell_pt(0, yu, ru, ru, th)
    Tl = ell_pt(0, yl, rl, rl, ph)
    pts = join(arc(0, yu, ru, ru, t_up - 10, th), line(Tu, Tl, 3), arc(0, yl, rl, rl, ph, t_lo - 10))
    sx = (w - p.W) / (2 * rl)
    S = lambda q: (w / 2 + q[0] * sx, q[1])
    pts = [S(q) for q in pts]
    rot = getattr(p, 'term_rot', 0.0)
    pu, tgu = S(ell_pt(0, yu, ru, ru, t_up)), (-ru * math.sin(rad(t_up)) * sx, ru * math.cos(rad(t_up)))
    pl, tgl = S(ell_pt(0, yl, rl, rl, t_lo)), (-rl * math.sin(rad(t_lo)) * sx, rl * math.cos(rad(t_lo)))
    G.stroke(pts, prof=getattr(p, 's_prof', None), cut0=normal_cut(pu, tgu, -rot), cut1=normal_cut(pl, tgl, rot))
    G.x0, G.x1 = 0, w
    return G


def g_dot(p, y=None):
    """free-standing round dot (the hinge), the size of the i dot,
    centred on half the x-height unless y is given."""
    G = Glyph(p, 'dot')
    r = p.W * p.dotk / 2
    y = p.X * 0.5 if y is None else y
    G.dot(r, y, r)
    G.x0, G.x1 = 0, 2 * r
    G.marks['dot'] = (r, y, r)
    return G


# ------------------------------------------------------------------ layout

class Word:
    def __init__(self):
        self.items = []   # (glyph, dx)
        self.x = 0.0
        self.prev = None

    def add(self, G, kern=0.0):
        if self.prev is not None:
            self.x += self.prev.rsb
        self.x += G.lsb + kern
        dx = self.x - G.x0
        self.items.append((G, dx))
        self.x = dx + G.x1
        self.prev = G


def bbox_polys(polys):
    xs = [q[0] for pl in polys for q in pl]
    ys = [q[1] for pl in polys for q in pl]
    return min(xs), min(ys), max(xs), max(ys)


def path_d(polys, s=1.0, tx=0.0, ty=0.0, eps=0.12, nd=2):
    """polys in font units (y up) -> svg path data (y down): X = x*s+tx, Y = -y*s+ty"""
    fmt = '%.' + str(nd) + 'f'
    out = []
    for pl in polys:
        pl = simplify_closed(pl, eps)
        if len(pl) < 3:
            continue
        pts = [(q[0] * s + tx, -q[1] * s + ty) for q in pl]
        seg = ['M' + (fmt % pts[0][0]).rstrip('0').rstrip('.') + ' ' + (fmt % pts[0][1]).rstrip('0').rstrip('.')]
        for q in pts[1:]:
            seg.append('L' + (fmt % q[0]).rstrip('0').rstrip('.') + ' ' + (fmt % q[1]).rstrip('0').rstrip('.'))
        out.append(''.join(seg) + 'Z')
    return ''.join(out)
