"""Check store text/artwork. --submission also requires real handset screenshots."""
import argparse
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1] / 'docs/play'


def png(path):
    data = path.read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', f'{path}: not PNG'
    assert data[12:16] == b'IHDR', f'{path}: missing IHDR'
    w, h, depth, color = struct.unpack('>IIBB', data[16:26])
    return w, h, depth, color


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--submission', action='store_true')
    args = parser.parse_args()
    for name, limit in [('title', 30), ('short-description', 80), ('full-description', 4000)]:
        text = (ROOT/'listing/en-US'/f'{name}.txt').read_text(encoding='utf-8').strip()
        assert 0 < len(text) <= limit, f'{name}: {len(text)} exceeds {limit}'
        print(f'{name}: {len(text)}/{limit}')
    icon = ROOT/'assets/icon.png'
    assert png(icon) == (512, 512, 8, 6), 'Icon must be 512x512 RGBA8'
    assert icon.stat().st_size <= 1024*1024, 'Icon exceeds 1 MiB'
    assert png(ROOT/'assets/feature-graphic.png') == (1024, 500, 8, 2)
    screenshots = list((ROOT/'assets/screenshots').glob('*.png'))
    for shot in screenshots:
        w, h, depth, color = png(shot)
        assert depth == 8 and color == 2, f'{shot}: RGB8 required, no alpha'
        assert min(w, h) >= 320 and max(w, h) <= 3840 and max(w, h) <= 2*min(w, h)
    if args.submission:
        assert len(screenshots) >= 2, 'Submission needs at least two actual handset screenshots'
    print(f'Artwork valid; {len(screenshots)} screenshots. This is not a policy or submission approval.')


if __name__ == '__main__':
    main()
