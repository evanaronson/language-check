"""The three r3 systems, all set from kit.py."""
import math
from kit import *

INK = '#111111'
PAPER = '#FAFAF8'

BUILD = {'l': g_l, 'i': g_i, 'n': g_n, 'g': g_g, 'u': g_u, 'o': g_o, 'a': g_a,
         'e': g_e, 'c': g_c, 'z': g_z, 't': g_t, 's': g_s}


def spaced(G, p):
    S = p.S
    R = S * p.__dict__.get('round_sb', 0.60)
    n = G.name
    if n in ('o', 'e', 'c'):
        G.lsb, G.rsb = R, R * (0.92 if n == 'e' else 0.80 if n == 'c' else 1.0)
    elif n in ('a', 'g'):
        G.lsb, G.rsb = R, S
    elif n.startswith('z'):
        G.lsb = G.rsb = S * 0.50
    elif n == 's':
        G.lsb = G.rsb = S * 0.58
    elif n == 't':
        G.lsb, G.rsb = S * 0.22, S * 0.30
    else:
        G.lsb = G.rsb = S
    return G


def setword(glyphs, kern):
    W = Word()
    prev = None
    for G in glyphs:
        k = 0.0
        if prev is not None:
            k = kern.get((prev.name, G.name), 0.0)
        W.add(G, k)
        prev = G
    return W


# ------------------------------------------------------------------ A  hinge
def params_A():
    return Params(W=100, contrast=0.86, S=50)


KERN_A = {('u', 'dot'): -6, ('dot', 'i'): -6, ('i', 'z'): 6, ('z', 'e'): -4, ('l', 'i'): 0,
          ('g', 'u'): -4, ('n', 'g'): -2, ('t', 'i'): 4, ('a', 't'): 4, ('s', 't'): 6}


def word_A(stem='lingu', p=None):
    p = p or params_A()
    gl = [spaced(BUILD[c](p), p) for c in stem]
    d = g_dot(p, y=p.X * 0.50, name='dot')
    d.lsb = d.rsb = p.S * 0.95
    gl.append(d)
    gl += [spaced(BUILD[c](p), p) for c in 'ize']
    return setword(gl, KERN_A), len(stem)


# ------------------------------------------------------------------ B  italic voice
def params_B():
    return Params(W=92, contrast=0.86, S=54)


def params_Bi(p):
    return p.copy(slant=11.0, pen_A=0.62, pen_B=0.27, pen_rot=25.0)


def ize_B(p):
    pi = params_Bi(p)
    i = g_i_pen(pi); i.lsb = p.S * 1.05
    i.x1 = i.marks['foot_x']; i.rsb = 0.74 * p.W
    z = g_z_pen(pi); z.lsb = 0; z.rsb = -0.05 * p.W
    e = g_e_pen(pi); e.lsb = 0; e.rsb = p.S * 0.5
    return [i, z, e]


def word_B(stem='lingu', p=None):
    p = p or params_B()
    gl = [spaced(BUILD[c](p), p) for c in stem]
    return setword(gl + ize_B(p), {}), len(stem)


# ------------------------------------------------------------------ C  stress accent + weight
def params_C():
    lo = Params(W=72, contrast=0.88, S=50)
    hi = Params(W=126, contrast=0.84, S=56, acc_ang=60, acc_gap=0.60, acc_h=1.00, acc_w=0.78, acc_dx=0.16)
    return lo, hi


KERN_C = {('u', 'i'): -4, ('i', 'z'): 2, ('z', 'e'): -6}


def word_C(stem='lingu', ps=None):
    lo, hi = ps or params_C()
    gl = [spaced(BUILD[c](lo), lo) for c in stem]
    i = g_i(hi, mark=None)
    accent(i, hi, hi.a)
    i.name = 'i'
    i = spaced(i, hi)
    i.lsb = (lo.S + hi.S) / 2
    gl += [i, spaced(g_z(hi), hi), spaced(g_e(hi), hi)]
    return setword(gl, KERN_C), len(stem)


SYSTEMS = {'A': word_A, 'B': word_B, 'C': word_C}
