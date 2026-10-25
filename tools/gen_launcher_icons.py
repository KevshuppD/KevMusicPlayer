#!/usr/bin/env python3
"""Genera los iconos del launcher por tema (ver LauncherIcon.kt).

- Temas normales: "Nota K" (corchea que forma una K) en vector, con los colores del tema.
- Temas Pixel Retro: "Vinilo K" rasterizado a 18 x 18 bloques con los colores de la paleta.

Escribe en app/src/main/res (drawables + mipmap-anydpi-v26) y, para la app debug, los
primeros planos con la insignia de la tuerca en app/src/debug/res/drawable.
Uso: python3 tools/gen_launcher_icons.py
"""
import math
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src")
MAIN = os.path.join(ROOT, "main", "res")
DEBUG = os.path.join(ROOT, "debug", "res")

# clave de app_theme -> (sufijo del recurso, principal, secundario, fondo). Mismos colores que Theme.kt / PixelPalettes.kt
THEMES = {
    "cyberpunk": ("cyberpunk", "#FF4081", "#B388FF", "#08090F"),
    "cyberpunk_purpura": ("purpura", "#D000FF", "#FF007F", "#08090F"),
    "petrol": ("petrol", "#00E5FF", "#80F1FF", "#040B0E"),
    "turquoise": ("turquoise", "#00F5D4", "#8FFFEF", "#020E0C"),
    "obsidian": ("obsidian", "#FFFFFF", "#B0B3C6", "#000000"),
    "monochrome": ("monochrome", "#111111", "#555555", "#FFFFFF"),
    "pixel": ("pixel", "#FF77A8", "#29ADFF", "#130E26"),
    "pixel_gameboy": ("pixel_gameboy", "#9BBC0F", "#E0F8D0", "#0F380F"),
    "pixel_nes": ("pixel_nes", "#F83800", "#3CBCFC", "#0B0B1E"),
    "pixel_c64": ("pixel_c64", "#67B6BD", "#B8C76F", "#2A1F66"),
    "pixel_virtualboy": ("pixel_virtualboy", "#FF1A1A", "#C00000", "#000000"),
    "pixel_amber": ("pixel_amber", "#FFB000", "#FFCC66", "#050300"),
    "pixel_phosphor": ("pixel_phosphor", "#33FF33", "#7DFF7D", "#000500"),
    "pixel_dungeon": ("pixel_dungeon", "#B13E53", "#41A6F6", "#0B0A12"),
}

HEADER = ('<?xml version="1.0" encoding="utf-8"?>\n'
          '<!-- Generado por tools/gen_launcher_icons.py: no editar a mano -->\n')
VECTOR_OPEN = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
               '    xmlns:aapt="http://schemas.android.com/aapt"\n'
               '    android:width="108dp"\n    android:height="108dp"\n'
               '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')

# Insignia de la app debug (tuerca naranja abajo a la derecha), la misma que tenía el icono anterior
DEBUG_BADGE = '''    <path android:fillColor="#12131A" android:pathData="M 76,60 A 16,16 0 1,1 75.99,60 Z" />
    <path android:fillColor="#FF6D00" android:pathData="M 76,62 A 14,14 0 1,1 75.99,62 Z" />
    <group android:translateX="65.8" android:translateY="65.8" android:scaleX="0.85" android:scaleY="0.85">
        <path android:fillColor="#FFFFFF" android:pathData="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87c-0.12,0.21 -0.08,0.47 0.12,0.61l2.03,1.58c-0.05,0.3 -0.09,0.63 -0.09,0.94s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94z M12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z" />
    </group>
'''


def rgb(h):
    return [int(h[i:i + 2], 16) for i in (1, 3, 5)]


# ---------- Nota K (vector) ----------

def note_k_paths(p, s, gradient=True):
    """Cabeza de la corchea, palo, banderín (brazo superior de la K) y brazo inferior."""
    if gradient:
        head_fill = (f'        <path android:pathData="M28,72 a10,7.5 0 1,0 20,0 a10,7.5 0 1,0 -20,0 Z">\n'
                     f'            <aapt:attr name="android:fillColor">\n'
                     f'                <gradient android:type="linear" android:startX="28" android:startY="64"\n'
                     f'                    android:endX="48" android:endY="80" android:startColor="{p}" android:endColor="{s}" />\n'
                     f'            </aapt:attr>\n        </path>\n')
    else:
        head_fill = f'        <path android:fillColor="{p}" android:pathData="M28,72 a10,7.5 0 1,0 20,0 a10,7.5 0 1,0 -20,0 Z" />\n'
    stroke = 'android:strokeWidth="6" android:strokeLineCap="round" android:fillColor="#00000000"'
    return (f'    <group android:rotation="-22" android:pivotX="38" android:pivotY="72">\n{head_fill}    </group>\n'
            f'    <path android:strokeColor="{p}" {stroke} android:pathData="M46,70 V30" />\n'
            f'    <path android:strokeColor="{p}" {stroke} android:pathData="M46,52 C58,48 66,40 72,30" />\n'
            f'    <path android:strokeColor="{s}" {stroke} android:pathData="M52,56 L74,78" />\n')


def note_k_background(p, bg, light):
    glow = "1A" if light else "4D"
    return (VECTOR_OPEN +
            f'    <path android:fillColor="{bg}" android:pathData="M0,0h108v108h-108z" />\n'
            f'    <path android:pathData="M0,0h108v108h-108z">\n'
            f'        <aapt:attr name="android:fillColor">\n'
            f'            <gradient android:type="radial" android:centerX="54" android:centerY="43" android:gradientRadius="76"\n'
            f'                android:startColor="#{glow}{p[1:]}" android:endColor="#00{p[1:]}" />\n'
            f'        </aapt:attr>\n    </path>\n</vector>\n')


# ---------- Vinilo K (pixel art) ----------

# El launcher muestra solo el centro del icono adaptativo: 72 dp de 108 en teoría, y el de Motorola recorta aún
# más (comprobado en el moto g35). Con la zona segura de 66 dp el dibujo queda con el mismo margen que en la
# vista previa; el vinilo pixel ocupa 50 dp, para que el borde nunca quede cortado.
SAFE = 66
SCALE = SAFE / 108
PIXEL_SIZE = 50
OFFSET = (108 - PIXEL_SIZE) / 2

# K de 6 x 7 bloques (trazos de 2) dentro de la etiqueta
PIXEL_K = [
    "KK..KK",
    "KK.KK.",
    "KKKK..",
    "KKK...",
    "KKKK..",
    "KK.KK.",
    "KK..KK",
]


def mix(a, b, t):
    A, B = rgb(a), rgb(b)
    return "#%02X%02X%02X" % tuple(round(A[i] * (1 - t) + B[i] * t) for i in range(3))


def outline(n, radius):
    """Celdas del círculo de ese radio que tocan el exterior: un contorno limpio de 1 bloque."""
    c = n / 2
    inside = [[math.hypot(x + 0.5 - c, y + 0.5 - c) <= radius for x in range(n)] for y in range(n)]

    def at(x, y):
        return 0 <= x < n and 0 <= y < n and inside[y][x]
    return inside, {(x, y) for y in range(n) for x in range(n)
                    if inside[y][x] and not (at(x - 1, y) and at(x + 1, y) and at(x, y - 1) and at(x, y + 1))}


def vinyl_pixels(p, s, bg, n=20):
    """Vinilo K dibujado bloque a bloque (sin suavizado): disco con borde y un surco del color principal
    atenuado, brillo arriba a la izquierda, etiqueta del color principal y la K en el color del fondo."""
    groove = mix("#000000", p, 0.45)
    disc = "#000000" if bg.upper() not in ("#000000", "#000500", "#050300") else mix("#000000", p, 0.12)
    grid = [[bg] * n for _ in range(n)]
    disc_in, rim = outline(n, 9.9)
    _, ring = outline(n, 7.2)
    label_in, _ = outline(n, 4.9)
    c = n / 2
    for y in range(n):
        for x in range(n):
            if not disc_in[y][x]:
                continue
            if (x, y) in rim:
                ang = math.degrees(math.atan2(y + 0.5 - c, x + 0.5 - c))
                grid[y][x] = s if -170 <= ang <= -100 else groove
            elif label_in[y][x]:
                grid[y][x] = p
            elif (x, y) in ring:
                grid[y][x] = groove
            else:
                grid[y][x] = disc
    for ky, row in enumerate(PIXEL_K):
        for kx, ch in enumerate(row):
            if ch == "K":
                grid[6 + ky][7 + kx] = bg
    return grid, PIXEL_SIZE / n, disc


def pixel_paths(grid, cell, skip):
    by_color = {}
    for y, row in enumerate(grid):
        for x, col in enumerate(row):
            if col == skip:
                continue
            by_color.setdefault(col, []).append(f"M{OFFSET + x * cell:.2f},{OFFSET + y * cell:.2f}h{cell:.2f}v{cell:.2f}h-{cell:.2f}z")
    return "".join(f'    <path android:fillColor="{col}" android:pathData="{"".join(rs)}" />\n' for col, rs in by_color.items())


def scaled(body):
    return (f'    <group android:scaleX="{SCALE:.4f}" android:scaleY="{SCALE:.4f}" android:pivotX="54" android:pivotY="54">\n'
            + body + '    </group>\n')


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main():
    for key, (name, p, s, bg) in THEMES.items():
        if key.startswith("pixel"):
            grid, cell, disc = vinyl_pixels(p, s, bg)
            fg_body = pixel_paths(grid, cell, bg)
            background = VECTOR_OPEN + f'    <path android:fillColor="{bg}" android:pathData="M0,0h108v108h-108z" />\n</vector>\n'
            mono_body = pixel_paths([["#FFFFFF" if c not in (bg, disc) else bg for c in row] for row in grid], cell, bg)
        else:
            fg_body = scaled(note_k_paths(p, s))
            background = note_k_background(p, bg, light=(key == "monochrome"))
            mono_body = scaled(note_k_paths("#FFFFFF", "#FFFFFF", gradient=False))
        write(os.path.join(MAIN, "drawable", f"ic_launcher_fg_{name}.xml"), HEADER + VECTOR_OPEN + fg_body + "</vector>\n")
        write(os.path.join(DEBUG, "drawable", f"ic_launcher_fg_{name}.xml"), HEADER + VECTOR_OPEN + fg_body + DEBUG_BADGE + "</vector>\n")
        write(os.path.join(MAIN, "drawable", f"ic_launcher_bg_{name}.xml"), HEADER + background)
        write(os.path.join(MAIN, "drawable", f"ic_launcher_mono_{name}.xml"), HEADER + VECTOR_OPEN + mono_body + "</vector>\n")
        write(os.path.join(MAIN, "mipmap-anydpi-v26", f"ic_launcher_{name}.xml"),
              HEADER + '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
              f'    <background android:drawable="@drawable/ic_launcher_bg_{name}" />\n'
              f'    <foreground android:drawable="@drawable/ic_launcher_fg_{name}" />\n'
              f'    <monochrome android:drawable="@drawable/ic_launcher_mono_{name}" />\n'
              '</adaptive-icon>\n')


if __name__ == "__main__":
    main()
