#!/usr/bin/env python3
"""Append classes.dex to a resource-only APK produced by aapt2 link."""
import shutil
import sys
import zipfile


def main():
    if len(sys.argv) != 4:
        print("usage: package.py <base.apk> <classes.dex> <out.apk>")
        return 2

    base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]

    with open(dex, "rb") as fh:
        payload = fh.read()

    shutil.copyfile(base, out)
    tmp = out + ".tmp"

    with zipfile.ZipFile(out, "r") as zin, \
            zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename == "classes.dex":
                continue
            zout.writestr(item, zin.read(item.filename))
        zout.writestr("classes.dex", payload)

    shutil.move(tmp, out)
    print("    classes.dex: %d bytes" % len(payload))
    return 0


if __name__ == "__main__":
    sys.exit(main())
