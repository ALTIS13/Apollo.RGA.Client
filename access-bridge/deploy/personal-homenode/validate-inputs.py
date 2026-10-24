"""Check deployment artifact format/hash, not source provenance or runnable behavior."""
import argparse
import hashlib
import pathlib
import re
import struct


def validate(binary, expected_sha256, architecture):
    machines = {"amd64": 62, "arm64": 183}
    if architecture not in machines or not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise ValueError("invalid artifact contract")
    data = pathlib.Path(binary).read_bytes()
    if hashlib.sha256(data).hexdigest() != expected_sha256:
        raise ValueError("artifact digest mismatch")
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError("not a supported little-endian ELF64 artifact")
    kind, machine, version = struct.unpack_from("<HHI", data, 16)
    if kind not in (2, 3) or machine != machines[architecture] or version != 1:
        raise ValueError("ELF target mismatch")
    offset = struct.unpack_from("<Q", data, 32)[0]
    header_size, entry_size, count = struct.unpack_from("<HHH", data, 52)
    if header_size != 64 or count == 0xFFFF or count and (
        entry_size != 56 or offset < 64 or offset + count * entry_size > len(data)
    ):
        raise ValueError("malformed ELF program header table")
    for index in range(count):
        if struct.unpack_from("<I", data, offset + index * entry_size)[0] == 3:
            raise ValueError("dynamic interpreter requires a different runtime contract")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=pathlib.Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--arch", choices=("amd64", "arm64"), required=True)
    arguments = parser.parse_args()
    try:
        validate(arguments.binary, arguments.sha256, arguments.arch)
    except (OSError, ValueError):
        parser.exit(1, "deployment artifact rejected\n")
    print("deployment artifact format and digest verified (not runtime proof)")


if __name__ == "__main__":
    main()
