#!/usr/bin/env python3
"""Check every ELF LOAD, and optionally exact staged cores in a finished APK/AAB."""
import argparse
from pathlib import Path
import struct
from zipfile import ZipFile

CORE_NAMES = ("libsingboxcore.so", "libxraycore.so")
MACHINES = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62)}


def verify_elf(data, abi):
    elf_class, machine = MACHINES[abi]
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4:6] != bytes((elf_class, 1)):
        raise ValueError("invalid ELF header")
    kind, actual_machine = struct.unpack_from("<HH", data, 16)
    if kind not in (2, 3) or actual_machine != machine:
        raise ValueError("wrong ELF type or ABI")
    if elf_class == 2:
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        fmt, expected_size = "<IIQQQQQQ", 56
    else:
        offset = struct.unpack_from("<I", data, 28)[0]
        size, count = struct.unpack_from("<HH", data, 42)
        fmt, expected_size = "<IIIIIIII", 32
    if size != expected_size or offset + size * count > len(data):
        raise ValueError("invalid or truncated ELF program headers")
    loads = 0
    for index in range(count):
        fields = struct.unpack_from(fmt, data, offset + index * size)
        if fields[0] != 1:
            continue
        loads += 1
        alignment = fields[-1]
        file_offset, virtual_address = fields[2:4] if elf_class == 2 else fields[1:3]
        if alignment < 16384 or alignment & (alignment - 1) or (virtual_address - file_offset) % 16384:
            raise ValueError(f"LOAD {index} has invalid 16 KB alignment")
    if not loads:
        raise ValueError("ELF has no LOAD segments")


def verify_stage(stage, abis, archive=None, core_names=CORE_NAMES):
    zipped = ZipFile(archive) if archive else None
    try:
        prefix = "base/lib" if archive and archive.suffix == ".aab" else "lib"
        for abi in abis:
            for name in core_names:
                path = stage / abi / name
                if not path.is_file():
                    raise ValueError(f"missing staged core {abi}/{name}")
                data = path.read_bytes()
                verify_elf(data, abi)
                if zipped:
                    member = f"{prefix}/{abi}/{name}"
                    if zipped.namelist().count(member) != 1:
                        raise ValueError(f"missing or duplicate packaged core {member}")
                    if zipped.read(member) != data:
                        raise ValueError(f"packaged core differs from staging: {member}")
    finally:
        if zipped:
            zipped.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=Path, default=Path("androidApp/jniLibs"))
    parser.add_argument("--abis", nargs="+", choices=MACHINES, required=True)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--core", choices=CORE_NAMES, action="append")
    args = parser.parse_args()
    try:
        verify_stage(args.stage, args.abis, args.archive, args.core or CORE_NAMES)
    except (ValueError, OSError) as error:
        parser.exit(1, f"Android core verification failed: {error}\n")
    print("Verified all LOAD segments" + (" and exact packaged cores" if args.archive else ""))


if __name__ == "__main__":
    main()
