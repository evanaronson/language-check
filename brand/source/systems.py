"""The lingu·ize word, set from kit.py.

The word is "lingu", a raised dot at half the x-height, then "ize". The dot is
the Catalan punt volat (as in l·l) used as a hinge: it marks where -ize snaps
onto any word. word() sets any stem the same way ("catalan" -> catalan·ize).
"""
from kit import Params, Word, g_a, g_c, g_dot, g_e, g_g, g_i, g_l, g_n, g_o, g_s, g_t, g_u, g_z

BUILD = {'l': g_l, 'i': g_i, 'n': g_n, 'g': g_g, 'u': g_u, 'o': g_o, 'a': g_a,
         'e': g_e, 'c': g_c, 'z': g_z, 't': g_t, 's': g_s}

KERN = {('u', 'dot'): -6, ('dot', 'i'): -6, ('i', 'z'): 6, ('z', 'e'): -4, ('l', 'i'): 0,
        ('g', 'u'): -4, ('n', 'g'): -2, ('t', 'i'): 4, ('a', 't'): 4, ('s', 't'): 6}


def params():
    """stem 100, bars 86, sidebearing 50."""
    return Params(W=100, contrast=0.86, S=50)


def spaced(G, p):
    """Set a letter's sidebearings by its shape: rounds sit closer than stems."""
    S = p.S
    R = S * getattr(p, 'round_sb', 0.60)
    n = G.name
    if n in ('o', 'e', 'c'):
        G.lsb, G.rsb = R, R * (0.92 if n == 'e' else 0.80 if n == 'c' else 1.0)
    elif n in ('a', 'g'):
        G.lsb, G.rsb = R, S
    elif n == 'z':
        G.lsb = G.rsb = S * 0.50
    elif n == 's':
        G.lsb = G.rsb = S * 0.58
    elif n == 't':
        G.lsb, G.rsb = S * 0.22, S * 0.30
    else:
        G.lsb = G.rsb = S
    return G


def word(stem='lingu', p=None):
    """Set stem + hinge dot + 'ize'. Returns (Word, index of the dot in Word.items)."""
    p = p or params()
    gl = [spaced(BUILD[c](p), p) for c in stem]
    d = g_dot(p)
    d.lsb = d.rsb = p.S * 0.95
    gl.append(d)
    gl += [spaced(BUILD[c](p), p) for c in 'ize']
    W = Word()
    prev = None
    for G in gl:
        W.add(G, KERN.get((prev.name, G.name), 0.0) if prev is not None else 0.0)
        prev = G
    return W, len(stem)
