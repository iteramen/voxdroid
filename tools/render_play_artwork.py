"""Render store artwork from Voxdroid's existing microphone mark (requires Pillow)."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/play/assets'


def mic(draw, x, y, size):
    # Same geometry as ic_launcher_foreground.xml, in a 108-unit viewport.
    def box(a, b, c, d):
        return tuple(round(v) for v in (x+a*size/108, y+b*size/108,
                                       x+c*size/108, y+d*size/108))
    width = max(1, round(4*size/108))
    draw.rounded_rectangle(box(44, 30, 64, 68), radius=10*size/108, fill='white')
    draw.arc(box(36, 40, 72, 76), 0, 180, fill='white', width=width)
    for coords in ((36, 52, 36, 58), (72, 52, 72, 58),
                   (54, 76, 54, 83), (46, 83, 62, 83)):
        draw.line(box(*coords), fill='white', width=width)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    icon = Image.new('RGB', (2048, 2048), 'black')
    mic(ImageDraw.Draw(icon), 0, 0, 2048)
    icon.resize((512, 512), Image.Resampling.LANCZOS).convert('RGBA').save(OUT/'icon.png')
    fonts = [Path('C:/Windows/Fonts/segoeui.ttf'),
             Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')]
    font = next((p for p in fonts if p.exists()), None)
    if font is None:
        raise SystemExit('Install Segoe UI or DejaVu Sans to render the feature graphic')
    scale = 2
    im = Image.new('RGB', (1024*scale, 500*scale), '#101820')
    d = ImageDraw.Draw(im)
    mic(d, 28*scale, 78*scale, 330*scale)
    d.text((370*scale, 133*scale), 'voxdroid', font=ImageFont.truetype(str(font), 76*scale), fill='white')
    for i, line in enumerate(('Speak. Read. Listen.', 'Your keyboard. Your servers.')):
        d.text((376*scale, (242+i*45)*scale), line,
               font=ImageFont.truetype(str(font), 29*scale), fill='#c6d6df')
    im.resize((1024, 500), Image.Resampling.LANCZOS).save(OUT/'feature-graphic.png')


if __name__ == '__main__':
    main()
