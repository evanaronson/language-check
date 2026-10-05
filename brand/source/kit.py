"""Linguize r3 lettering kit.

One parametric engine for every letter in all three systems.
Skeleton (centre-line) paths are swept with an axis-aligned elliptical nib
(a = W/2 horizontal, b = H/2 vertical), so vertical strokes are W thick,
horizontal strokes H thick, curves interpolate exactly between them.
Joins thin through per-stroke width profiles; terminals are trimmed by exact
cut lines; overlapping contours are unioned by the nonzero fill rule.
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


def cubic(p0, p1, p2, p3, n=160):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append((u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
                    u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]))
    return out


def join(*segs):
    out = []
    for s in segs:
        for p in s:
            if out and dist(out[-1], p) < 1e-6:
                continue
            out.append(p)
    return out


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


def clip(poly, q, d):
    """Sutherland-Hodgman: keep the part with side(p) >= 0."""
    out = []
    n = len(poly)
    for i in range(n):
        P, Q = poly[i], poly[(i + 1) % n]
        sp, sq = side(P, q, d), side(Q, q, d)
        if sp >= 0:
            out.append(P)
        if (sp >= 0) != (sq >= 0):
            t = sp / (sp - sq)
            out.append(lerp(P, Q, t))
    return out


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


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def ramp(f0, u1, u0=0.0):
    """width factor f0 at u<=u0 easing to 1 at u>=u1 (start of stroke)"""
    return lambda u: f0 + (1 - f0) * smooth((u - u0) / (u1 - u0))


def ramp_end(f0, u1):
    return lambda u: f0 + (1 - f0) * smooth((1 - u) / (1 - u1))


# ------------------------------------------------------------------ params

class Params:
    def __init__(self, **kw):
        self.X = 500.0          # x-height
        self.O = 10.0           # overshoot of rounds
        self.ASC = 690.0        # ascender (l)
        self.DESC = -205.0      # descender (g)
        self.W = 86.0           # vertical stem
        self.contrast = 0.86    # horizontal / vertical
        self.slant = 0.0        # degrees (italic shear, about y = X/2)
        self.wide = 1.0         # width multiplier
        self.S = 52.0           # straight sidebearing
        self.dotk = 1.13        # dot diameter / stem
        self.dot_top = None     # top of i dot (default ASC + 4)
        self.f_join = 0.70      # width factor where arches/bowls join stems
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

    def copy(self, **kw):
        d = dict(self.__dict__)
        for key in ('H', 'a', 'b', 'wn', 'wo', 'wz', 'wa', 'we', 'wc', 'ws'):
            d.pop(key, None)
        if 'ASC' in kw and 'dot_top' not in kw:
            d['dot_top'] = None
        d.update(kw)
        return Params(**d)


# ------------------------------------------------------------------ glyph

class Glyph:
    def __init__(self, p, name):
        self.p = p
        self.name = name
        self.polys = []          # oriented polygons (+ fill, - hole)
        self.x0 = 0.0            # body extents (upright) for spacing
        self.x1 = 0.0
        self.lsb = p.S
        self.rsb = p.S
        self.marks = {}          # named anchor points for diagrams

    # shear about the mid x-height so spacing stays put
    def sh(self, pt):
        t = math.tan(rad(self.p.slant))
        return (pt[0] + (pt[1] - self.p.X / 2) * t, pt[1])

    def shs(self, pts):
        if not self.p.slant:
            return pts
        return [self.sh(q) for q in pts]

    def fill(self, poly):
        self.polys.append(orient(poly, +1))

    def hole(self, poly):
        self.polys.append(orient(poly, -1))

    def stroke(self, pts, prof=None, a=None, b=None, cut0=None, cut1=None, ext=None):
        """Open stroke. cut0/cut1: (point, dir) cut lines in upright coords."""
        p = self.p
        a = p.a if a is None else a
        b = p.b if b is None else b
        P = self.shs(pts)
        L, R = offsets(P, a, b, prof)
        if cut1 is not None:
            q, d = self._cut(cut1)
            L, R = trim(L, q, d, True), trim(R, q, d, True)
        if cut0 is not None:
            q, d = self._cut(cut0)
            L, R = trim(L, q, d, False), trim(R, q, d, False)
        poly = L + R[::-1]
        self.fill(poly)
        return L, R

    def _cut(self, c):
        q, d = c
        if self.p.slant:
            q2 = self.sh((q[0] + d[0], q[1] + d[1]))
            q = self.sh(q)
            d = (q2[0] - q[0], q2[1] - q[1])
        return q, d

    def ring(self, pts, prof=None):
        P = self.shs(pts)
        L, R = offsets(P, self.p.a, self.p.b, prof, closed=True)
        if abs(area(L)) > abs(area(R)):
            L, R = R, L
        self.fill(R)
        self.hole(L)
        return L, R

    def dot(self, cx, cy, r, n=96):
        c = self.sh((cx, cy)) if self.p.slant else (cx, cy)
        self.fill([(c[0] + r * math.cos(2 * math.pi * i / n), c[1] + r * math.sin(2 * math.pi * i / n)) for i in range(n)])

    def poly(self, pts):
        self.fill(self.shs(pts))

    def clip_all(self, q, d):
        """clip every fill polygon by a half plane (upright coords)."""
        q, d = self._cut((q, d))
        self.polys = [orient(clip(pl, q, d), 1 if area(pl) > 0 else -1) for pl in self.polys]

    def transform(self, fn):
        self.polys = [[fn(q) for q in pl] for pl in self.polys]

    def bbox(self):
        xs = [q[0] for pl in self.polys for q in pl]
        ys = [q[1] for pl in self.polys for q in pl]
        return min(xs), min(ys), max(xs), max(ys)


def normal_cut(pt, tangent, rot=0.0):
    """cut line through pt perpendicular to tangent (optionally rotated)."""
    tx, ty = tangent
    l = math.hypot(tx, ty)
    nx, ny = -ty / l, tx / l
    if rot:
        c, s = math.cos(rad(rot)), math.sin(rad(rot))
        nx, ny = nx * c - ny * s, nx * s + ny * c
    return (pt, (nx, ny))


def ell_pt(cx, cy, rx, ry, deg):
    t = rad(deg)
    return (cx + rx * math.cos(t), cy + ry * math.sin(t))


def ell_tan(rx, ry, deg):
    t = rad(deg)
    return (-rx * math.sin(t), ry * math.cos(t))


# ------------------------------------------------------------------ letters
# every builder returns a Glyph whose body spans x0..x1 (upright)

def g_l(p):
    G = Glyph(p, 'l')
    G.stroke(line((p.a, 0), (p.a, p.ASC)))
    G.x0, G.x1 = 0, p.W
    G.marks['stem'] = p.a
    return G


def i_dot_center(p):
    r = p.W * p.dotk / 2
    return p.dot_top - r, r


def g_i(p, mark='dot'):
    G = Glyph(p, 'i')
    G.stroke(line((p.a, 0), (p.a, p.X)))
    if mark == 'dot':
        cy, r = i_dot_center(p)
        G.dot(p.a, cy, r)
    G.x0, G.x1 = 0, p.W
    return G


def accent(G, p, cx, W=None):
    """acute accent: straight stroke rising right, both ends cut level."""
    W = W or p.W
    ang = p.__dict__.get('acc_ang', 62.0)
    y0 = p.X + p.__dict__.get('acc_gap', 0.95) * W
    y1 = y0 + p.__dict__.get('acc_h', 1.80) * W
    T = p.__dict__.get('acc_w', 1.0) * W
    foot = cx + p.__dict__.get('acc_dx', 0.10) * W
    u = (math.cos(rad(ang)), math.sin(rad(ang)))
    n = (u[1], -u[0])
    o = (foot, y0)
    pa = (o[0] - 200 * u[0], o[1] - 200 * u[1])
    pb = (o[0] + 900 * u[0], o[1] + 900 * u[1])
    h = T / 2
    poly = [(pa[0] - n[0] * h, pa[1] - n[1] * h), (pb[0] - n[0] * h, pb[1] - n[1] * h),
            (pb[0] + n[0] * h, pb[1] + n[1] * h), (pa[0] + n[0] * h, pa[1] + n[1] * h)]
    poly = clip(poly, (0, y0), (1, 0))
    poly = clip(poly, (0, y1), (-1, 0))
    G.poly(poly)
    G.marks['acc'] = (y0, y1)
    G.marks['acc_poly'] = len(G.polys) - 1


def g_n(p, w=None, name='n'):
    """stem + arch. The arch is the region between two ellipses that share
    their centre height yc: the outer one starts inside the stem (xo_l), the
    inner one is tangent to the stem's right edge, so the arch thins as it
    leaves the stem and every contour is a pure ellipse."""
    w = w or p.wn
    W, H, X, O = p.W, p.H, p.X, p.O
    G = Glyph(p, name)
    G.poly([(0, 0), (W, 0), (W, X), (0, X)])
    xo_l = p.__dict__.get('arch_in', 0.42) * W
    xi_l = W - p.__dict__.get('arch_bite', 0.0) * W
    rxo = (w - xo_l) / 2
    cxo = xo_l + rxo
    ryo = rxo * p.__dict__.get('arch_k', 1.0)
    yc = X + O - ryo
    rxi = (w - W - xi_l) / 2
    cxi = xi_l + rxi
    ryi = ryo - H * p.__dict__.get('arch_top', 1.0)
    outer = arc(cxo, yc, rxo, ryo, 180, 0)
    inner = arc(cxi, yc, rxi, ryi, 0, 180)
    G.poly(outer + [(w, 0), (w - W, 0)] + inner)
    G.x0, G.x1 = 0, w
    G.marks['arch'] = (cxo, yc, rxo, ryo, cxi, rxi, ryi)
    return G


def g_u(p, w=None):
    w = w or p.wn
    G = g_n(p.copy(slant=0), w, 'u')
    G.transform(lambda q: (w - q[0], p.X - q[1]))
    if p.slant:
        G.p = p
        G.transform(G.sh)
    G.p = p
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
    inset = p.__dict__.get('bowl_inset', 0.30) * p.W
    wb = w - inset
    rx = (wb - p.W) / 2
    cx = p.a + rx
    ry = p.X / 2 + p.O - p.b
    pts = arc(cx, p.X / 2, rx, ry, -180, 180)[:-1]
    sig = p.__dict__.get('bowl_sig', 52.0)
    f0 = p.__dict__.get('bowl_f', 0.66)
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
    cxb, rxb, _ = _bowl(G, p, w)
    a, b = p.a, p.b
    rxh = p.__dict__.get('g_hook_rx', 0.92) * rxb + 0.0
    ryh = p.__dict__.get('g_hook_ry', 0.58) * (p.X / 2)
    yh = p.DESC - p.O + b + ryh
    cx = w - a - rxh
    end = p.__dict__.get('g_end', -158.0)
    pts = join(line((w - a, p.X), (w - a, yh)), arc(cx, yh, rxh, ryh, 0, end - 8))
    cut = normal_cut(ell_pt(cx, yh, rxh, ryh, end), ell_tan(rxh, ryh, end), p.__dict__.get('term_rot', 0.0))
    G.stroke(pts, cut1=cut)
    G.x0, G.x1 = 0, w
    return G


def g_e(p, w=None, name='e'):
    w = w or p.we
    G = Glyph(p, name)
    a, b, X = p.a, p.b, p.X
    rx, ry = (w - p.W) / 2, X / 2 + p.O - b
    cx, cy = w / 2, X / 2
    bar_y = p.__dict__.get('e_bar', 0.515) * X          # bar centre
    hb = p.__dict__.get('e_barw', 0.84) * p.W / 2       # half bar thickness
    yb = bar_y - hb                                      # bar bottom = cut
    th0 = math.degrees(math.asin((yb - cy) / ry)) - 8
    end = 360 + p.__dict__.get('e_end', -36.0)
    pts = arc(cx, cy, rx, ry, th0, end + 10)
    cut = normal_cut(ell_pt(cx, cy, rx, ry, end), ell_tan(rx, ry, end), p.__dict__.get('term_rot', 0.0))
    G.stroke(pts, cut0=((0, yb), (-1, 0)), cut1=cut)
    # crossbar: from the left stroke to the right stroke's centre line
    G.stroke(line((cx - rx, bar_y), (cx + rx, bar_y)), b=hb)
    G.x0, G.x1 = 0, w
    G.marks['bar'] = (bar_y, hb)
    return G


def g_c(p, w=None):
    w = w or p.wc
    G = Glyph(p, 'c')
    rx, ry = (w - p.W) / 2, p.X / 2 + p.O - p.b
    cx, cy = w / 2, p.X / 2
    t0 = p.__dict__.get('c_open', 40.0)
    rot = p.__dict__.get('term_rot', 0.0)
    pts = arc(cx, cy, rx, ry, t0 - 10, 360 - t0 + 10)
    G.stroke(pts,
             cut0=normal_cut(ell_pt(cx, cy, rx, ry, t0), ell_tan(rx, ry, t0), -rot),
             cut1=normal_cut(ell_pt(cx, cy, rx, ry, 360 - t0), ell_tan(rx, ry, 360 - t0), rot))
    G.x0, G.x1 = 0, w
    return G


def z_poly(p, w, H, X, k_frac=0.30, top_inset=0.035, Td=None):
    """upright z polygon; acute corners blunted by a short vertical cut k."""
    wt = w * (1 - top_inset)       # top bar a touch shorter (optical)
    tl = w * top_inset * 0.5
    k = k_frac * H
    if Td is None:
        Td = 0.95 * p.W
    phi = math.atan2(X - 2 * k, wt)
    for _ in range(8):
        # nib thickness in the diagonal's normal direction
        nib = 2 * math.sqrt((p.a * math.sin(phi)) ** 2 + (p.b * math.cos(phi)) ** 2)
        T = Td / p.W * nib if Td else nib
        R = math.hypot(wt, X - 2 * k)
        phi = math.atan2(X - 2 * k, wt) + math.asin(T / R)
    tn = math.tan(phi)
    xb = wt - (X - k - H) / tn
    xa = (X - H - k) / tn
    return [(0, 0), (w, 0), (w, H), (xb, H), (wt, X - k), (wt, X), (tl, X), (tl, X - H), (xa, X - H), (0, k)], phi


def g_z(p, w=None):
    w = w or p.wz
    G = Glyph(p, 'z')
    poly, phi = z_poly(p, w, p.H * p.__dict__.get('z_bar', 1.0), p.X,
                       p.__dict__.get('z_blunt', 0.30), p.__dict__.get('z_top', 0.035),
                       p.__dict__.get('z_diag', 0.93) * p.W)
    G.poly(poly)
    G.x0, G.x1 = 0, w
    return G


def g_t(p):
    G = Glyph(p, 't')
    bl = p.__dict__.get('t_left', 0.80) * p.W
    br = p.__dict__.get('t_right', 1.05) * p.W
    top = p.X + p.__dict__.get('t_top', 0.52) * (p.ASC - p.X)
    G.stroke(line((bl + p.a, 0), (bl + p.a, top)))
    G.stroke(line((0, p.X - p.b), (bl + p.W + br, p.X - p.b)))
    G.x0, G.x1 = 0, bl + p.W + br
    return G


def g_s(p, w=None):
    """two stacked circles joined by their inner common tangent, x-stretched."""
    w = w or p.ws
    G = Glyph(p, 's')
    a, b, X, O = p.a, p.b, p.X, p.O
    k = p.__dict__.get('s_ratio', 1.07)        # lower bowl / upper bowl
    span = X + 2 * O - 2 * b                   # skeleton height
    ru = span / 2 / (1 + k) * p.__dict__.get('s_r', 0.93)
    rl = ru * k
    yu = X + O - b - ru
    yl = -O + b + rl
    D = yu - yl
    th = 180 + math.degrees(math.asin((ru + rl) / D))   # tangent pt, upper circle
    ph = th - 180                                       # tangent pt, lower circle
    t_up = p.__dict__.get('s_t1', 32.0)
    t_lo = p.__dict__.get('s_t2', -148.0)
    Tu = ell_pt(0, yu, ru, ru, th)
    Tl = ell_pt(0, yl, rl, rl, ph)
    pts = join(arc(0, yu, ru, ru, t_up - 10, th), line(Tu, Tl, 3), arc(0, yl, rl, rl, ph, t_lo - 10))
    sx = (w - p.W) / (2 * rl)
    S = lambda q: (w / 2 + q[0] * sx, q[1])
    pts = [S(q) for q in pts]
    rot = p.__dict__.get('term_rot', 0.0)
    pu, tgu = S(ell_pt(0, yu, ru, ru, t_up)), (-ru * math.sin(rad(t_up)) * sx, ru * math.cos(rad(t_up)))
    pl, tgl = S(ell_pt(0, yl, rl, rl, t_lo)), (-rl * math.sin(rad(t_lo)) * sx, rl * math.cos(rad(t_lo)))
    G.stroke(pts, prof=p.__dict__.get('s_prof'), cut0=normal_cut(pu, tgu, -rot), cut1=normal_cut(pl, tgl, rot))
    G.x0, G.x1 = 0, w
    return G


def g_dot(p, y=None, name='dot'):
    """free-standing round dot (hinge / punt volat), same size as the i dot."""
    G = Glyph(p, name)
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
        return dx

    def polys(self):
        out = []
        for G, dx in self.items:
            for pl in G.polys:
                out.append([(q[0] + dx, q[1]) for q in pl])
        return out


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


# ------------------------------------------------------------------ italic (system B)

def g_i_it(p, mark='dot'):
    """italic i: slanted stem whose foot turns right into a short flick."""
    G = Glyph(p, 'i.it')
    a, b, X, O = p.a, p.b, p.X, p.O
    rf = p.__dict__.get('it_foot_r', 0.30) * X
    yf = rf + b - O
    end = p.__dict__.get('it_foot_end', 302.0)
    pts = join(line((a, X + 60), (a, yf)), arc(a + rf, yf, rf, rf, 180, end + 10))
    G.stroke(pts, cut0=((0, X), (1, 0)),
             cut1=normal_cut(ell_pt(a + rf, yf, rf, rf, end), ell_tan(rf, rf, end)))
    if mark == 'dot':
        cy, r = i_dot_center(p)
        G.dot(a, cy, r)
    G.x0, G.x1 = 0, p.W
    G.marks['foot'] = (a + rf, yf, rf)
    return G


def g_z_it(p, w=None):
    """italic swash z: point-symmetric curls on the top-left and bottom-right,
    same radius as the i's foot; straight diagonal with clean corners."""
    w = w or p.wz
    G = Glyph(p, 'z.it')
    a, b, X = p.a, p.b, p.X
    rc = p.__dict__.get('z_curl_r', 0.24) * X
    cend = p.__dict__.get('z_curl_end', 180.0)      # where the top curl starts
    xl, xr = b * 0.0 + p.__dict__.get('z_inset', 0.0), w   # skeleton extents
    yt, yb = X - b, b
    # top stroke: curl (start) -> bar, overshooting the corner (clipped later)
    top = join(arc(xl + rc, yt - rc, rc, rc, cend + 10, 90), line((xl + rc, yt), (xr + 120, yt)))
    bot = join(line((-120, yb), (xr - rc, yb)), arc(xr - rc, yb + rc, rc, rc, 270, 360 - (cend - 180) + 10))
    # diagonal skeleton between the inner corners
    dtr = (xr - p.__dict__.get('z_dg', 0.55) * p.W, yt)
    dbl = (p.__dict__.get('z_dg', 0.55) * p.W, yb)
    A, B = G.sh(dtr) if p.slant else dtr, G.sh(dbl) if p.slant else dbl
    dx, dy = B[0] - A[0], B[1] - A[1]
    L = math.hypot(dx, dy)
    ux, uy = dx / L, dy / L
    nx, ny = -uy, ux
    f = p.__dict__.get('z_diag', 0.95)
    sx, sy = support(nx, ny, a * f, b * f / p.contrast * 1.0)
    A2, B2 = (A[0] - 200 * ux, A[1] - 200 * uy), (B[0] + 200 * ux, B[1] + 200 * uy)
    diag = [(A2[0] + sx, A2[1] + sy), (B2[0] + sx, B2[1] + sy), (B2[0] - sx, B2[1] - sy), (A2[0] - sx, A2[1] - sy)]
    diag = clip(diag, (0, X), (-1, 0))
    diag = clip(diag, (0, 0), (1, 0))
    # edges of the diagonal (post shear): "+" side and "-" side
    eplus = ((A[0] + sx, A[1] + sy), (ux, uy))
    eminus = ((A[0] - sx, A[1] - sy), (ux, uy))
    # which side is upper-right?  the normal (nx,ny) points to the left of A->B
    # A->B goes down-left, so its left normal points down-right: '+' = lower-right edge
    cterm = normal_cut(ell_pt(xl + rc, yt - rc, rc, rc, cend), ell_tan(rc, rc, cend))
    bterm = normal_cut(ell_pt(xr - rc, yb + rc, rc, rc, 360 - (cend - 180)), ell_tan(rc, rc, 360 - (cend - 180)))
    Lt, Rt = G.stroke(top, cut0=cterm)
    tp = G.polys.pop()
    Lb, Rb = G.stroke(bot, cut1=bterm)
    bp = G.polys.pop()
    # keep the top bar left of the diagonal's lower-right edge; bottom bar right of upper-left edge
    q, d = eplus
    tp = clip(tp, q, (-d[0], -d[1]))
    q, d = eminus
    bp = clip(bp, q, d)
    for pl in (tp, bp, diag):
        G.fill(pl)
    G.x0, G.x1 = 0, w
    return G


def fillet_path(P, R, step=0.5, lstep=4.0):
    """polyline P with corner radii R[i] at interior vertices -> smooth skeleton."""
    out = [P[0]]
    for i in range(1, len(P) - 1):
        V = P[i]
        u1 = (V[0] - P[i - 1][0], V[1] - P[i - 1][1])
        l1 = math.hypot(*u1); u1 = (u1[0] / l1, u1[1] / l1)
        u2 = (P[i + 1][0] - V[0], P[i + 1][1] - V[1])
        l2 = math.hypot(*u2); u2 = (u2[0] / l2, u2[1] / l2)
        cr = u1[0] * u2[1] - u1[1] * u2[0]
        dot = max(-1, min(1, u1[0] * u2[0] + u1[1] * u2[1]))
        delta = math.acos(dot)
        r = R[i - 1]
        t = r * math.tan(delta / 2)
        A = (V[0] - u1[0] * t, V[1] - u1[1] * t)
        sgn = 1 if cr > 0 else -1                     # left turn = ccw
        nrm = (-u1[1] * sgn, u1[0] * sgn)
        C = (A[0] + nrm[0] * r, A[1] + nrm[1] * r)
        a0 = math.degrees(math.atan2(A[1] - C[1], A[0] - C[0]))
        a1 = a0 + sgn * math.degrees(delta)
        out = join(out, line(out[-1], A, lstep), arc(C[0], C[1], r, r, a0, a1, step))
    out = join(out, line(out[-1], P[-1], lstep))
    return out


def g_z_soft(p, w=None):
    """italic 'written' z: one stroke, rounded turns, exit flick like the i foot."""
    w = w or p.wz
    G = Glyph(p, 'z.it')
    a, b, X = p.a, p.b, p.X
    yt, yb = X - b, b - p.__dict__.get('zs_drop', 0.0)
    rc = p.__dict__.get('zs_rc', 0.62) * p.W
    rf = p.__dict__.get('zs_rf', 0.55) * X
    fl = p.__dict__.get('zs_flick', 0.20) * X          # rise of the exit
    xr = w - a
    x_end = w - a + p.__dict__.get('zs_ext', 0.10) * X
    pts = [(a * 0.0 + p.__dict__.get('zs_l', 0.05) * w, yt), (xr, yt), (a * 0.9, yb), (x_end, yb),
           (x_end + fl * 1.2, yb + fl * 1.0)]
    sk = fillet_path(pts, [rc, rc, rf])
    # start: vertical cut at the bar's left end; end: normal cut at the exit
    q = sk[-1]; qq = sk[-6]
    tg = (q[0] - qq[0], q[1] - qq[1])
    ext = (q[0] + tg[0] * 3, q[1] + tg[1] * 3)
    sk2 = join(sk, line(q, ext, 2))
    G.stroke(sk2, cut1=normal_cut(q, tg))
    G.x0, G.x1 = 0, w
    return G


def g_z_flick(p, w=None):
    """italic z = the upright z construction, slanted, whose bottom bar leaves
    through the same exit flick as the italic i's foot (same radius, same cut)."""
    w = w or p.wz
    G = Glyph(p, 'z.it')
    X, H = p.X, p.H * p.__dict__.get('z_bar', 1.0)
    rf = p.__dict__.get('zf_r', p.__dict__.get('it_foot_r', 0.30)) * X
    end = p.__dict__.get('zf_end', p.__dict__.get('it_foot_end', 302.0))
    xm = w - p.__dict__.get('zf_len', 0.55) * rf     # where the flick starts
    # target perpendicular diagonal thickness after the shear
    Tt = p.__dict__.get('z_diag', 0.93) * p.W
    Td = Tt
    t = math.tan(rad(p.slant))
    for _ in range(6):
        poly, phi = z_poly(p, xm + 0, H, X, p.__dict__.get('z_blunt', 0.30), p.__dict__.get('z_top', 0.035), Td)
        # measure: direction of diagonal after shear
        d0 = (math.cos(phi), math.sin(phi))
        d1 = (d0[0] + d0[1] * t, d0[1])
        # perpendicular thickness scales by |n1| geometry: T' = T * cos(angle change)
        s0 = math.atan2(d0[1], d0[0]); s1 = math.atan2(d1[1], d1[0])
        Tm = Td * math.sin(s1) / math.sin(s0)
        Td *= Tt / Tm
    # move the bottom bar's right end to xm and the top bar keeps its width
    poly = [(q[0], q[1]) for q in poly]
    poly[1] = (xm, 0); poly[2] = (xm, H)
    if p.__dict__.get('zf_hook'):
        poly[6] = (w - xm, X); poly[7] = (w - xm, X - H)
    G.poly(poly)
    a, b = p.a, p.b
    yb = b + 0.0
    cy = rf + b - p.O * 0.0
    cx = xm
    pts = join(line((xm - 60, yb), (xm, yb)), arc(cx, yb + rf, rf, rf, 270, end + 10))
    G.stroke(pts, cut1=normal_cut(ell_pt(cx, yb + rf, rf, rf, end), ell_tan(rf, rf, end)))
    if p.__dict__.get('zf_hook'):
        # point-symmetric entry hook on the top-left (same radius, same sweep)
        yt = X - b
        xh = w - xm
        sweep = end - 270
        pts = join(line((xh + 60, yt), (xh, yt)), arc(xh, yt - rf, rf, rf, 90, 90 + sweep + 10))
        G.stroke(pts, cut1=normal_cut(ell_pt(xh, yt - rf, rf, rf, 90 + sweep), ell_tan(rf, rf, 90 + sweep)))
    G.x0, G.x1 = 0, w
    return G


# ------------------------------------------------------------------ pen italic (system B option)

def support_rot(nx, ny, A, B, rot):
    c, s = math.cos(rad(rot)), math.sin(rad(rot))
    # normal into nib frame
    mx, my = nx * c + ny * s, -nx * s + ny * c
    sx, sy = support(mx, my, A, B)
    return (sx * c - sy * s, sx * s + sy * c)


def pen_offsets(pts, A, B, rot, prof=None):
    n = len(pts)
    left, right = [], []
    s = [0.0]
    for i in range(1, n):
        s.append(s[-1] + dist(pts[i - 1], pts[i]))
    L = s[-1] or 1
    for i in range(n):
        if i == 0:
            d = (pts[1][0] - pts[0][0], pts[1][1] - pts[0][1])
        elif i == n - 1:
            d = (pts[-1][0] - pts[-2][0], pts[-1][1] - pts[-2][1])
        else:
            d = (pts[i + 1][0] - pts[i - 1][0], pts[i + 1][1] - pts[i - 1][1])
        l = math.hypot(*d)
        nx, ny = -d[1] / l, d[0] / l
        f = prof(s[i] / L) if prof else 1.0
        sx, sy = support_rot(nx, ny, A * f, B * f, rot)
        left.append((pts[i][0] + sx, pts[i][1] + sy))
        right.append((pts[i][0] - sx, pts[i][1] - sy))
    return left, right


def nib_stamp(c, A, B, rot, n=64):
    cr, sr = math.cos(rad(rot)), math.sin(rad(rot))
    out = []
    for i in range(n):
        t = 2 * math.pi * i / n
        x, y = A * math.cos(t), B * math.sin(t)
        out.append((c[0] + x * cr - y * sr, c[1] + x * sr + y * cr))
    return out


class Pen:
    """broad-edged pen: ellipse with half-length A, half-width B, at angle rot."""
    def __init__(self, G, A, B, rot):
        self.G, self.A, self.B, self.rot = G, A, B, rot

    def stroke(self, pts, caps=True, prof=None):
        P = self.G.shs(pts)
        L, R = pen_offsets(P, self.A, self.B, self.rot, prof)
        self.G.fill(L + R[::-1])
        if caps:
            f0 = prof(0) if prof else 1
            f1 = prof(1) if prof else 1
            self.G.fill(nib_stamp(P[0], self.A * f0, self.B * f0, self.rot))
            self.G.fill(nib_stamp(P[-1], self.A * f1, self.B * f1, self.rot))


def pen_params(p):
    A = p.__dict__.get('pen_A', 0.60) * p.W
    B = p.__dict__.get('pen_B', 0.26) * p.W
    return A, B, p.__dict__.get('pen_rot', 28.0)


def pen_ext(A, B, rot):
    """half extents of the nib: horizontal (for vertical strokes) and vertical."""
    hx = math.sqrt((A * math.cos(rad(rot))) ** 2 + (B * math.sin(rad(rot))) ** 2)
    hy = math.sqrt((A * math.sin(rad(rot))) ** 2 + (B * math.cos(rad(rot))) ** 2)
    return hx, hy


def g_i_pen(p):
    G = Glyph(p, 'i.it')
    A, B, rot = pen_params(p)
    hx, hy = pen_ext(A, B, rot)
    pen = Pen(G, A, B, rot)
    X, O = p.X, p.O
    r = p.__dict__.get('pen_foot', 0.15) * X
    x = hx
    yb = hy - O
    top = X - hy
    end = p.__dict__.get('pen_foot_end', 312)
    pts = join(line((x, top), (x, yb + r)), arc(x + r, yb + r, r, r, 180, end))
    pen.stroke(pts)
    cy, rr = i_dot_center(p)
    k = p.__dict__.get('pen_dot', 0.80)
    G.fill(nib_stamp(G.sh((x, cy)), A * k, A * k * 0.66, rot))
    G.x0, G.x1 = 0, 2 * hx
    G.marks['foot_x'] = x + r + r * math.cos(rad(end)) + hx
    return G


def g_z_pen(p, w=None):
    G = Glyph(p, 'z.it')
    A, B, rot = pen_params(p)
    hx, hy = pen_ext(A, B, rot)
    pen = Pen(G, A, B, rot)
    X = p.X
    w = w or p.wz
    yt = X - hy
    yb = hy
    xl = hx * 0.6
    xr = w - hx * 0.6
    lead = p.__dict__.get('pen_lead', 0.10) * X
    top = cubic((xl, yt - lead), (xl + 0.06 * w, yt), (xl + 0.20 * w, yt), (xr, yt), 60)
    pen.stroke(top)
    pen.stroke(line((xr, yt), (xl, yb)))
    dip = p.__dict__.get('pen_dip', 0.16) * X
    tail = p.__dict__.get('pen_tail', 0.16) * w
    bot = cubic((xl, yb), (xl + 0.45 * w, yb + 0.05 * X), (xr - 0.05 * w, yb + 0.06 * X), (xr + tail, yb - dip), 100)
    pen.stroke(bot)
    G.x0, G.x1 = 0, w
    return G


def g_e_pen(p, w=None):
    G = Glyph(p, 'e.it')
    A, B, rot = pen_params(p)
    hx, hy = pen_ext(A, B, rot)
    pen = Pen(G, A, B, rot)
    X, O = p.X, p.O
    w = w or p.we
    rx = w / 2 - hx
    ry = X / 2 + O - hy
    cx, cy = w / 2, X / 2
    yb = p.__dict__.get('e_bar', 0.50) * X
    th0 = math.degrees(math.asin((yb - cy) / ry))
    xs = cx - rx + hx * 0.5
    end = 360 + p.__dict__.get('pen_e_end', -38)
    pen.stroke(line((cx - rx, yb), (cx + rx * math.cos(rad(th0)), yb)), caps=False)
    P = arc(cx, cy, rx, ry, th0 - 14, end)
    Ps = G.shs(P)
    L, R = pen_offsets(Ps, A, B, rot)
    cutq, cutd = (0, yb - hy), (1, 0)
    L, R = trim(L, cutq, cutd, False), trim(R, cutq, cutd, False)
    G.fill(L + R[::-1])
    G.fill(nib_stamp(Ps[-1], A, B, rot))
    G.x0, G.x1 = 0, w
    return G
