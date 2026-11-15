#!/usr/bin/env python3
"""Build and validate the gen5w.autolock NEMESIS package and USB installer bundle."""
from __future__ import annotations

import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SHORT_MAX = 64
DETAIL_MAX = 256
VER_RE = re.compile(r"^\d+(\.\d+)*$")
ID_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9._-]{0,63}$")


def parse_ini(text: str) -> dict[tuple[str, str], str]:
    """Same rules as NEMESIS native/ini.c."""
    out: dict[tuple[str, str], str] = {}
    sec = ""
    i, n, line = 0, len(text), 1
    while i < n:
        while i < n and text[i] in " \t":
            i += 1
        if i >= n:
            break
        if text[i] in "\r\n":
            if text[i] == "\r" and i + 1 < n and text[i + 1] == "\n":
                i += 1
            i += 1
            line += 1
            continue
        j = i
        while j < n and text[j] not in "\r\n":
            j += 1
        if text[i] in "#;[":
            if text[i] == "[" and "]" in text[i:j]:
                sec = text[i + 1:text.index("]", i)].strip()
            i = j
            continue
        eq = text.find("=", i, j)
        if eq < 0:
            i = j
            continue
        key = text[i:eq].strip()
        k = eq + 1
        while k < n and text[k] in " \t":
            k += 1
        start = line
        if k < n and text[k] == '"':
            k += 1
            val = []
            while True:
                if k >= n:
                    raise ValueError(f"line {start}: unterminated quoted value")
                c = text[k]
                k += 1
                if c == '"':
                    break
                if c == "\\" and k < n and text[k] in '"\\':
                    c = text[k]
                    k += 1
                elif c == "\r":
                    if k < n and text[k] == "\n":
                        k += 1
                    c = "\n"
                    line += 1
                elif c == "\n":
                    line += 1
                val.append(c)
            value = "".join(val)
            while k < n and text[k] not in "\r\n":
                k += 1
            i = k
        else:
            value = text[k:j].strip()
            i = j
        if len(value.encode()) >= 1024:
            raise ValueError(f"line {start}: value too long")
        if key:
            out.setdefault((sec, key), value)
    return out


def validate(meta_path: Path) -> list[str]:
    """Returns warnings; raises SystemExit on errors the head unit would reject."""
    warnings: list[str] = []
    try:
        kv = parse_ini(meta_path.read_bytes().decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as e:
        raise SystemExit(f"{meta_path}: {e}")

    def get(key: str) -> str | None:
        return kv.get(("nemesis", key))

    errors = []
    ver = get("version") or "1.0"
    if not VER_RE.match(ver):
        errors.append(f"bad version={ver}")
    pid = get("id") or ""
    if not ID_RE.match(pid) or ".." in pid:
        errors.append(f"bad or missing id={pid!r}")
    pv = get("package-version")
    if pv and not VER_RE.match(pv):
        errors.append(f"package-version must be dotted numbers: {pv}")
    for key, limit in (("short-desc", SHORT_MAX), ("detailed-desc", DETAIL_MAX)):
        val = get(key)
        if val is not None and len(val) > limit:
            errors.append(f"{key} is {len(val)} characters (max {limit})")
    if errors:
        raise SystemExit(f"{meta_path}: " + "; ".join(errors))
    return warnings


def normalize_lf(path: Path) -> bytes:
    """Return file bytes with CRLF normalized to LF."""
    return path.read_bytes().replace(b"\r\n", b"\n")


def build_zip(pkg_dir: Path, out_zip: Path) -> None:
    meta = pkg_dir / "NEMESIS.data"
    if not meta.is_file():
        raise SystemExit(f"Missing {meta}")

    for warning in validate(meta):
        print(f"warning: {meta}: {warning}")

    out_zip.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for p in sorted(pkg_dir.rglob("*")):
            if not p.is_file():
                continue
            rel = p.relative_to(pkg_dir).as_posix()
            if rel.startswith("app_src/"):
                continue
            info = zipfile.ZipInfo(rel)
            if p.suffix in {".sh", ".data"} or p.name == "NEMESIS.data":
                data = normalize_lf(p)
                mode = 0o755 if p.suffix == ".sh" else 0o644
                info.external_attr = (0o100000 | mode) << 16
                zf.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED)
            else:
                zf.write(p, rel)
    print(f"Wrote {out_zip} ({out_zip.stat().st_size} bytes)")


def main() -> None:
    pkg_dir = ROOT / "packages" / "gen5w.autolock"
    meta = parse_ini((pkg_dir / "NEMESIS.data").read_bytes().decode("utf-8"))
    ver = meta.get(("nemesis", "package-version")) or "1.0.0"
    zip_name = f"gen5w.autolock-{ver}.zip"

    inst = ROOT / "dist" / "autolock-installer"
    pkg_zip = inst / zip_name
    build_zip(pkg_dir, pkg_zip)
    installer_data = inst / "NEMESIS.data"
    installer_data.write_bytes(
        f"[nemesis]\nversion=1.0\ntype=install\npath={zip_name}\n".encode("utf-8")
    )
    print(f"Wrote {installer_data}")

    usb_zip = inst / f"gen5w.autolock-{ver}-USB.zip"
    with zipfile.ZipFile(usb_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.write(installer_data, "NEMESIS.data")
        zf.write(pkg_zip, zip_name)
    print(f"Wrote {usb_zip} ({usb_zip.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
