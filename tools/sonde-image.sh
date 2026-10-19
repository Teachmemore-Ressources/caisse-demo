#!/bin/bash
python3 -c "import PIL; print('PIL', PIL.__version__)" 2>&1 | head -1
for c in convert magick cwebp dwebp ffmpeg; do printf '%s: %s\n' "$c" "$(command -v $c || echo absent)"; done
python3 -c "
from PIL import Image
im = Image.open('/mnt/c/Users/armel/AppData/Local/Temp/claude/C--Users-armel-Downloads/143f169d-603f-47f9-b6f4-82f8e7a5ce22/images/4.webp'); print(im.size, im.mode)" 2>&1 | tail -1
