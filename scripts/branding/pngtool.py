#!/usr/bin/env python3
"""Minimal dependency-free PNG reader/writer/resizer.

Only what the launcher-icon generator needs:

* decode 8-bit, non-interlaced PNGs (greyscale, RGB, palette, greyscale+alpha,
  RGBA) into straight-alpha RGBA rows;
* encode 8-bit RGBA (or RGB) PNGs;
* resize with a box (area-average) filter, which is the right choice for
  downscaling artwork and avoids the aliasing a nearest-neighbour copy would
  give;
* composite and pad onto a canvas.

Using only the standard library keeps the icon pipeline reproducible on a
build runner with no image tooling installed.
"""
import struct
import zlib

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

_CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}


def read_png(path):
    """Return (width, height, pixels) with pixels a list of RGBA bytearrays."""
    with open(path, "rb") as handle:
        data = handle.read()
    if data[:8] != PNG_SIGNATURE:
        raise ValueError(f"{path}: not a PNG")

    pos = 8
    width = height = depth = colour = None
    palette = None
    transparency = None
    idat = bytearray()

    while pos + 8 <= len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        chunk_type = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + length]
        if chunk_type == b"IHDR":
            width, height, depth, colour, _comp, _filt, interlace = struct.unpack(">IIBBBBB", payload)
            if depth != 8:
                raise ValueError(f"{path}: only 8-bit PNGs are supported (got {depth})")
            if interlace != 0:
                raise ValueError(f"{path}: interlaced PNGs are not supported")
        elif chunk_type == b"PLTE":
            palette = payload
        elif chunk_type == b"tRNS":
            transparency = payload
        elif chunk_type == b"IDAT":
            idat += payload
        elif chunk_type == b"IEND":
            break
        pos += 12 + length

    if width is None:
        raise ValueError(f"{path}: no IHDR chunk")

    channels = _CHANNELS[colour]
    stride = width * channels
    raw = zlib.decompress(bytes(idat))

    rows = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset:offset + stride])
        offset += stride
        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                up = previous[i]
                up_left = previous[i - channels] if i >= channels else 0
                estimate = left + up - up_left
                da = abs(estimate - left)
                db = abs(estimate - up)
                dc = abs(estimate - up_left)
                if da <= db and da <= dc:
                    predictor = left
                elif db <= dc:
                    predictor = up
                else:
                    predictor = up_left
                line[i] = (line[i] + predictor) & 0xFF
        elif filter_type != 0:
            raise ValueError(f"{path}: unknown PNG filter {filter_type}")
        rows.append(line)
        previous = line

    pixels = []
    for line in rows:
        row = bytearray(width * 4)
        for x in range(width):
            base = x * channels
            if colour == 0:
                grey = line[base]
                row[x * 4:x * 4 + 4] = bytes((grey, grey, grey, 255))
            elif colour == 2:
                row[x * 4:x * 4 + 3] = line[base:base + 3]
                row[x * 4 + 3] = 255
            elif colour == 3:
                index = line[base]
                row[x * 4:x * 4 + 3] = palette[index * 3:index * 3 + 3]
                row[x * 4 + 3] = transparency[index] if transparency and index < len(transparency) else 255
            elif colour == 4:
                grey = line[base]
                alpha = line[base + 1]
                row[x * 4:x * 4 + 4] = bytes((grey, grey, grey, alpha))
            else:  # colour == 6
                row[x * 4:x * 4 + 4] = line[base:base + 4]
        pixels.append(row)
    return width, height, pixels


def write_png(path, width, height, pixels, rgb=False):
    """Write 8-bit RGBA (or RGB when rgb=True) PNG rows with filter type 0."""
    channels = 3 if rgb else 4
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        if rgb:
            for x in range(width):
                raw += row[x * 4:x * 4 + 3]
        else:
            for x in range(width):
                raw += row[x * 4:x * 4 + 4]

    def chunk(tag, payload):
        return (struct.pack(">I", len(payload)) + tag + payload
                + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 2 if rgb else 6, 0, 0, 0)
    with open(path, "wb") as handle:
        handle.write(PNG_SIGNATURE)
        handle.write(chunk(b"IHDR", header))
        handle.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        handle.write(chunk(b"IEND", b""))


def resize(pixels, src_w, src_h, dst_w, dst_h):
    """Box-filter resize of RGBA rows, premultiplied to avoid halos."""
    if src_w == dst_w and src_h == dst_h:
        return [bytearray(row) for row in pixels]
    out = []
    for y in range(dst_h):
        y0 = y * src_h // dst_h
        y1 = max(y0 + 1, (y + 1) * src_h // dst_h)
        row = bytearray(dst_w * 4)
        for x in range(dst_w):
            x0 = x * src_w // dst_w
            x1 = max(x0 + 1, (x + 1) * src_w // dst_w)
            r = g = b = a = 0
            count = 0
            for sy in range(y0, y1):
                src_row = pixels[sy]
                for sx in range(x0, x1):
                    i = sx * 4
                    alpha = src_row[i + 3]
                    r += src_row[i] * alpha
                    g += src_row[i + 1] * alpha
                    b += src_row[i + 2] * alpha
                    a += alpha
                    count += 1
            i = x * 4
            if a:
                # The accumulator is already alpha-weighted, so the average is
                # simply r // a (the 255 scale cancels out).
                row[i] = min(255, r // a)
                row[i + 1] = min(255, g // a)
                row[i + 2] = min(255, b // a)
                row[i + 3] = a // count
        out.append(row)
    return out


def solid(width, height, colour):
    r, g, b = colour
    return [bytearray(bytes((r, g, b, 255)) * width) for _ in range(height)]


def paste(canvas, canvas_w, canvas_h, image, image_w, image_h, offset_x, offset_y):
    """Alpha-composite `image` onto `canvas` at the given offset."""
    for y in range(image_h):
        cy = offset_y + y
        if cy < 0 or cy >= canvas_h:
            continue
        target = canvas[cy]
        source = image[y]
        for x in range(image_w):
            cx = offset_x + x
            if cx < 0 or cx >= canvas_w:
                continue
            i = x * 4
            alpha = source[i + 3]
            if not alpha:
                continue
            j = cx * 4
            if alpha == 255:
                target[j:j + 4] = source[i:i + 4]
                continue
            inverse = 255 - alpha
            for channel in range(3):
                target[j + channel] = (source[i + channel] * alpha
                                       + target[j + channel] * inverse) // 255
            target[j + 3] = alpha + target[j + 3] * inverse // 255
    return canvas
