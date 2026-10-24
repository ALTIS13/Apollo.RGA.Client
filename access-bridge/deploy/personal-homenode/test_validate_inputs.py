import hashlib
import importlib.util
import pathlib
import struct
import tempfile
import unittest


class ArtifactInputTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = pathlib.Path(__file__).with_name("validate-inputs.py")
        if not path.exists():
            raise AssertionError("Linux artifact validator is missing")
        spec = importlib.util.spec_from_file_location("artifact_inputs", path)
        cls.validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.validator)

    def artifact(self, machine=62, interpreter=False):
        header = bytearray(64 + (56 if interpreter else 0))
        header[:7] = b"\x7fELF\x02\x01\x01"
        struct.pack_into("<HHI", header, 16, 2, machine, 1)
        struct.pack_into("<Q", header, 32, 64 if interpreter else 0)
        struct.pack_into("<HHH", header, 52, 64, 56, 1 if interpreter else 0)
        if interpreter:
            struct.pack_into("<I", header, 64, 3)
        return bytes(header)

    def check(self, data, arch="amd64", expected=None):
        with tempfile.TemporaryDirectory() as directory:
            binary = pathlib.Path(directory) / "node"
            binary.write_bytes(data)
            digest = expected or hashlib.sha256(data).hexdigest()
            self.validator.validate(binary, digest, arch)

    def test_matching_static_linux_headers_accepted_for_supported_architectures(self):
        self.check(self.artifact())
        self.check(self.artifact(machine=183), arch="arm64")

    def test_windows_pe_rejected_even_with_correct_digest(self):
        with self.assertRaises(ValueError):
            self.check(b"MZ" + bytes(100))

    def test_valid_elf_for_other_host_architecture_rejected(self):
        with self.assertRaises(ValueError):
            self.check(self.artifact(machine=183))

    def test_dynamic_interpreter_rejected_without_runtime_libc_assumption(self):
        with self.assertRaises(ValueError):
            self.check(self.artifact(interpreter=True))

    def test_tampered_artifact_rejected_by_digest(self):
        with self.assertRaises(ValueError):
            self.check(self.artifact(), expected="0" * 64)

    def test_truncated_program_header_table_rejected(self):
        with self.assertRaises(ValueError):
            self.check(self.artifact(interpreter=True)[:80])


if __name__ == "__main__":
    unittest.main()
