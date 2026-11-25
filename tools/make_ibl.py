"""Makes the Holo look's lighting for the 3D truck: a dark studio with soft overhead and side panels and a cyan
glow along the horizon, drawn here from numbers (no photo), then run through Filament's cmgen (same version as
the app's Filament) into the cube map the app loads.

    python3 tools/make_ibl.py /path/to/filament/bin/cmgen

Writes app/src/main/assets/scene/holo_ibl.ktx and prints the light's spherical harmonics."""
import math, os, shutil, struct, subprocess, sys, tempfile

import numpy as np

W, H = 1024, 512


def environment():
    v = (np.arange(H) + 0.5) / H                    # 0 at the top
    u = (np.arange(W) + 0.5) / W
    el = (0.5 - v)[:, None] * math.pi               # elevation, +pi/2 straight up
    az = (u[None, :] - 0.5) * 2 * math.pi           # azimuth
    img = np.zeros((H, W, 3), np.float32)
    img[:] = (0.010, 0.016, 0.026)                  # the dark room
    up = np.clip(el / (math.pi / 2), 0, 1)
    img += up[..., None] * np.array([0.02, 0.04, 0.06], np.float32)
    # a big soft panel overhead
    over = np.exp(-((math.pi / 2 - el) / 0.45) ** 4)
    img += over[..., None] * np.array([2.6, 2.7, 2.8], np.float32)
    # four tall strip panels round the sides, so paint and glass catch a line of light from any side
    for a0 in (math.radians(45), math.radians(135), math.radians(-45), math.radians(-135)):
        d = np.angle(np.exp(1j * (az - a0)))
        strip = np.exp(-(d / 0.16) ** 6) * np.exp(-((el - 0.45) / 0.33) ** 6)
        img += strip[..., None] * np.array([1.5, 1.6, 1.7], np.float32)
    # the hologram's cyan, low round the horizon
    band = np.exp(-((el - 0.05) / 0.10) ** 2)
    img += band[..., None] * np.array([0.10, 0.45, 0.60], np.float32)
    # a dark floor with a faint cyan sheen
    floor = np.clip(-el / 0.2, 0, 1)
    img = img * (1 - floor[..., None] * 0.85) + floor[..., None] * np.array([0.006, 0.02, 0.03], np.float32)
    return img


def rle_line(channel):
    out = bytearray()
    i, n = 0, len(channel)
    while i < n:
        run = 1
        while i + run < n and run < 127 and channel[i + run] == channel[i]:
            run += 1
        if run >= 3:
            out += bytes((128 + run, channel[i]))
            i += run
            continue
        j = i
        while j < n and j - i < 128:
            if j + 2 < n and channel[j] == channel[j + 1] == channel[j + 2]:
                break
            j += 1
        out += bytes((j - i,)) + bytes(channel[i:j])
        i = j
    return out


def write_hdr(path, img):
    m = img.max(axis=2)
    e = np.where(m > 1e-32, np.floor(np.log2(np.maximum(m, 1e-32))) + 1, 0)
    scale = np.where(m > 1e-32, 256.0 / np.exp2(e), 0)
    rgbe = np.zeros((H, W, 4), np.uint8)
    rgbe[..., :3] = np.clip(img * scale[..., None], 0, 255).astype(np.uint8)
    rgbe[..., 3] = np.where(m > 1e-32, e + 128, 0).astype(np.uint8)
    with open(path, 'wb') as f:
        f.write(b'#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y %d +X %d\n' % (H, W))
        for y in range(H):
            f.write(bytes((2, 2, W >> 8, W & 255)))
            for c in range(4):
                f.write(rle_line(rgbe[y, :, c].tolist()))


def main():
    cmgen = sys.argv[1]
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, 'app', 'src', 'main', 'assets', 'scene')
    os.makedirs(out, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        hdr = os.path.join(tmp, 'holo.hdr')
        write_hdr(hdr, environment())
        deploy = os.path.join(tmp, 'holo')        # cmgen names its files after this folder
        subprocess.run([cmgen, '--quiet', '--format=ktx', '--size=128', '--deploy=' + deploy, hdr], check=True)
        shutil.copy(os.path.join(deploy, 'holo_ibl.ktx'), os.path.join(out, 'holo_ibl.ktx'))
        sh = os.path.join(deploy, 'sh.txt')
        if os.path.exists(sh):
            print(open(sh).read())
    print('wrote', os.path.join(out, 'holo_ibl.ktx'))


if __name__ == '__main__':
    main()
