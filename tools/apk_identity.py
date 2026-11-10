#!/usr/bin/env python3
"""Strict APK identity checks for the video-gift dual build.

The dual build ships two APKs that must never be confused with each other:

* the coexisting package ``me.rerere.orangechat.videogifts`` (shared debug key,
  explicitly not production-safe), and
* the unsigned update candidate ``me.rerere.orangechat`` with a strictly
  validated ``versionCode`` (default 166, must be > 165), which only becomes an
  installable update after the original builder signs it with the original
  production certificate.

This module parses ``aapt2 dump badging`` and ``apksigner verify --print-certs``
output into typed values and validates them fail-closed.  Certificate digests
are compared as complete 64 hex character SHA-256 strings; no substring or
"contains" comparison is used anywhere.

"Unsigned" is never inferred from silence.  It needs positive evidence from two
independent sources:

* ``apksigner`` printed an explicit ``DOES NOT VERIFY`` verdict together with a
  recognised "no signature" diagnostic (a real unsigned APK reports
  ``ERROR: Missing META-INF/MANIFEST.MF``), and nothing that looks like a JVM
  crash or a mis-launched tool; and
* the ZIP container itself carries neither a ``META-INF`` JAR signature nor an
  ``APK Sig Block 42`` APK Signing Block, and every byte between the last ZIP
  entry and the central directory is accounted for.

An ``apksigner`` exit status of 1 alone proves nothing: it is also what a JVM
launch failure, a broken signature or an unparsable APK produce.  Those cases
are therefore classified explicitly and fail closed:

* tool/launch/parse failures (``JAVA_HOME is not set``, ``Exception in
  thread "main"``, ``Malformed APK``, ...) raise :class:`ToolError`;
* a broken signature also prints ``DOES NOT VERIFY``, but with a diagnostic
  outside the minimal unsigned allow-list (for example ``ERROR: APK Signature
  Scheme v3 signer #1: APK integrity check failed``), so it is rejected;
* a signed APK yields ``Verifies`` plus certificate digests and is rejected.

The ZIP scan is what makes the claim independent of ``apksigner`` wording: a
v2/v3-only APK has no ``META-INF`` signature entries at all, so a v2-signed
build is only distinguishable from an unsigned one by its signing block.

Public metadata (package, version, ABIs, signer digests, APK sha256) is printed
as ``key=value`` lines so CI can attach it to the build artifact.  No secrets
are ever read or printed.

Usage:
    python tools/apk_identity.py inspect --apk app.apk
    python tools/apk_identity.py verify-coexist --apk app.apk
    python tools/apk_identity.py verify-update-candidate --apk app.apk \
        --expected-version-code 166
    python tools/apk_identity.py verify-signed-update --apk app.apk \
        --expected-version-code 166
    python tools/apk_identity.py check-buildconfig \
        --file app/build/generated/source/buildConfig/videoUpdate/.../BuildConfig.java \
        --expected-version-code 166 --expected-version-name 2.2.9
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

# ---------------------------------------------------------------------------
# Fixed, public identity constants (documented in docs/GITHUB_DUAL_BUILD.md).
# ---------------------------------------------------------------------------

PRODUCTION_PACKAGE = "me.rerere.orangechat"
COEXIST_PACKAGE = "me.rerere.orangechat.videogifts"
LIQUIDGLASS_PACKAGE = "me.rerere.orangechat.liquidglass"

# versionCode of the last production release that must keep its data.
ORIGINAL_VERSION_CODE = 165
UPDATE_MIN_VERSION_CODE = ORIGINAL_VERSION_CODE + 1

# Certificate of the original release APK (public metadata, from
# `apksigner verify --print-certs` of OrangeChat-2.2.9-...-release.apk).
PRODUCTION_CERT_SHA256 = (
    "49ceec2c9d193a1b515ff7c5aa3a8bb745e2dbcfdb8eb0b99aa184b88472ae64"
)

# Certificate of the committed shared debug keystore app/debug.keystore
# (alias androiddebugkey).  This is a public, non-secret test key: it must never
# be used to carry the production update identity.
SHARED_DEBUG_CERT_SHA256 = (
    "ff516315e577235b64ff356dbc856c053b7e589ae636dd8bc4301e528b46d116"
)

DEFAULT_ABIS = ("arm64-v8a",)

COEXIST_LABEL = "西瓜瓣\u00b7视频共存"

_HEX64_RE = re.compile(r"^[0-9a-f]{64}$")

# Minimal, exact allow-list of "there is no signature at all" diagnostics.
# Captured from a real Gradle-produced unsigned APK with build-tools 37.0.0:
#   DOES NOT VERIFY
#   ERROR: Missing META-INF/MANIFEST.MF
# Anything else that apksigner reports after ``DOES NOT VERIFY`` is *not*
# evidence of being unsigned; it is evidence of a broken or unrecognised APK.
UNSIGNED_DIAGNOSTICS = ("ERROR: Missing META-INF/MANIFEST.MF",)

# Every one of these means apksigner never evaluated a signature: the JVM was
# not launched, the tool failed, or the APK could not be parsed.  A run like
# that exits 1 and prints no certificates, which is exactly why it must be
# recognised explicitly instead of being read as "unsigned".
TOOL_FAILURE_MARKERS = (
    "JAVA_HOME is not set",
    "no 'java' command could be found",
    'Exception in thread "main"',
    "Exception in thread 'main'",
    "Could not find or load main class",
    "Unable to access jarfile",
    "UnsupportedClassVersionError",
    "Caused by:",
    "Malformed APK",
    "not a ZIP archive",
    "ApkFormatException",
    "ZipFormatException",
    "at com.android.apksig",
    "at com.android.apksigner",
    "Unrecognized option",
    "Failed to determine APK's minimum supported platform version",
)

# JAR signing (v1) lives in META-INF; a v2/v3 signature lives in the
# "APK Sig Block 42" block between the last ZIP entry and the central directory.
JAR_SIGNATURE_ENTRY_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC", "MANIFEST.MF")
APK_SIGNING_BLOCK_MAGIC = b"APK Sig Block 42"

# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ApkMetadata:
    """Metadata parsed from `aapt2 dump badging`."""

    package: str
    version_code: int
    version_name: str
    label: str | None
    native_codes: tuple[str, ...]
    min_sdk: int | None
    target_sdk: int | None

    @property
    def abis(self) -> tuple[str, ...]:
        return self.native_codes


@dataclass(frozen=True)
class ApkSignature:
    """Signature state parsed from `apksigner verify --print-certs`."""

    verifies: bool
    schemes: dict[str, bool]
    signer_count: int | None
    cert_sha256: tuple[str, ...]
    malformed: tuple[str, ...]
    does_not_verify: bool = False
    unsigned_diagnostics: tuple[str, ...] = ()
    other_diagnostics: tuple[str, ...] = ()
    tool_failures: tuple[str, ...] = ()

    @property
    def unique_certs(self) -> tuple[str, ...]:
        seen: list[str] = []
        for digest in self.cert_sha256:
            if digest not in seen:
                seen.append(digest)
        return tuple(seen)

    @property
    def unsigned_evidence(self) -> bool:
        """True only when apksigner *positively* said "this has no signature".

        Requires the explicit ``DOES NOT VERIFY`` verdict AND a recognised
        unsigned diagnostic AND no tool failure, no malformed digest and no
        certificate at all.  An empty output, a JVM crash or a broken-signature
        diagnostic all fail this test.
        """

        return (
            not self.verifies
            and self.does_not_verify
            and bool(self.unsigned_diagnostics)
            and not self.malformed
            and not self.tool_failures
            and not self.cert_sha256
        )


@dataclass(frozen=True)
class ZipSignatureEvidence:
    """What the ZIP container itself says about signatures.

    Independent of ``apksigner``: a v2/v3-only APK carries *no* ``META-INF``
    signature entries, so the presence of the APK Signing Block is the only
    container-level difference between "signed with v2/v3" and "unsigned".
    """

    jar_signature_entries: tuple[str, ...]
    signing_block: bool
    trailing_bytes: int
    errors: tuple[str, ...]

    @property
    def unsigned_evidence(self) -> bool:
        return (
            not self.jar_signature_entries
            and not self.signing_block
            and self.trailing_bytes == 0
            and not self.errors
        )


_PACKAGE_LINE_RE = re.compile(
    r"^package: name='(?P<package>[^']+)'"
    r"(?: versionCode='(?P<version_code>[0-9]+)')?"
    r"(?: versionName='(?P<version_name>[^']*)')?"
)
_LABEL_LINE_RE = re.compile(r"^application-label:'(?P<label>.*)'$")
_NATIVE_CODE_LINE_RE = re.compile(r"^native-code:(?P<rest>.*)$")
_NATIVE_CODE_VALUE_RE = re.compile(r"'([^']+)'")
_MIN_SDK_RE = re.compile(r"^minSdkVersion:'(?P<value>[0-9]+)'$")
_TARGET_SDK_RE = re.compile(r"^targetSdkVersion:'(?P<value>[0-9]+)'$")

_VERIFIES_RE = re.compile(r"^Verifies\s*$")
_DOES_NOT_VERIFY_RE = re.compile(r"DOES NOT VERIFY")
_SCHEME_RE = re.compile(r"^Verified using (?P<scheme>v[0-9.]+) scheme .*: (?P<value>true|false)$")
_SIGNER_COUNT_RE = re.compile(r"^Number of signers: (?P<count>[0-9]+)$")
_CERT_SHA256_RE = re.compile(
    r"^(?:V[0-9](?:\.[0-9])? Signer|Signer #[0-9]+|Signer)"
    r":? certificate SHA-256 digest: (?P<digest>\S+)$"
)
def parse_badging(text: str) -> ApkMetadata:
    """Parse `aapt2 dump badging` output.

    Raises ValueError when the mandatory package line is missing or malformed:
    an APK that cannot be identified must never be treated as valid.
    """

    package: str | None = None
    version_code: int | None = None
    version_name = ""
    label: str | None = None
    native_codes: list[str] = []
    min_sdk: int | None = None
    target_sdk: int | None = None

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if package is None:
            match = _PACKAGE_LINE_RE.match(line)
            if match:
                package = match.group("package")
                raw_code = match.group("version_code")
                if raw_code is None:
                    raise ValueError("badging package line has no versionCode")
                version_code = int(raw_code)
                version_name = match.group("version_name") or ""
                continue
        if label is None:
            match = _LABEL_LINE_RE.match(line)
            if match:
                label = match.group("label")
                continue
        match = _NATIVE_CODE_LINE_RE.match(line)
        if match:
            native_codes.extend(_NATIVE_CODE_VALUE_RE.findall(match.group("rest")))
            continue
        match = _MIN_SDK_RE.match(line)
        if match:
            min_sdk = int(match.group("value"))
            continue
        match = _TARGET_SDK_RE.match(line)
        if match:
            target_sdk = int(match.group("value"))
            continue

    if package is None or version_code is None:
        raise ValueError("aapt2 badging output has no parsable package/versionCode line")
    # Keep order but drop duplicates.
    deduped: list[str] = []
    for abi in native_codes:
        if abi not in deduped:
            deduped.append(abi)
    return ApkMetadata(
        package=package,
        version_code=version_code,
        version_name=version_name,
        label=label,
        native_codes=tuple(deduped),
        min_sdk=min_sdk,
        target_sdk=target_sdk,
    )


def parse_apksigner(text: str) -> ApkSignature:
    """Parse `apksigner verify --print-certs` output.

    Three outcomes are kept strictly apart:

    * verified — ``Verifies`` plus at least one complete 64 hex certificate;
    * unsigned — ``DOES NOT VERIFY`` plus a diagnostic from
      :data:`UNSIGNED_DIAGNOSTICS` and no certificate;
    * everything else — tool/launch/parse failures, broken signatures,
      truncated digests, unparsable output.

    A signature is only considered present when apksigner reports a successful
    verification AND every reported SHA-256 digest is a complete 64 hex string.
    Truncated, uppercase-normalised-away or otherwise malformed digests are
    recorded as malformed and make callers fail closed.
    """

    verifies = False
    does_not_verify = False
    schemes: dict[str, bool] = {}
    signer_count: int | None = None
    digests: list[str] = []
    malformed: list[str] = []
    unsigned_diagnostics: list[str] = []
    other_diagnostics: list[str] = []
    tool_failures: list[str] = []

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if any(marker in line for marker in TOOL_FAILURE_MARKERS):
            tool_failures.append(line)
            continue
        if _VERIFIES_RE.match(line):
            verifies = True
            does_not_verify = False
            continue
        if _DOES_NOT_VERIFY_RE.search(line):
            verifies = False
            does_not_verify = True
            continue
        if line.startswith("ERROR:"):
            if line in UNSIGNED_DIAGNOSTICS:
                unsigned_diagnostics.append(line)
            else:
                other_diagnostics.append(line)
            continue
        match = _SCHEME_RE.match(line)
        if match:
            schemes[match.group("scheme")] = match.group("value") == "true"
            continue
        match = _SIGNER_COUNT_RE.match(line)
        if match:
            signer_count = int(match.group("count"))
            continue
        match = _CERT_SHA256_RE.match(line)
        if match:
            digest = match.group("digest")
            if _HEX64_RE.match(digest):
                digests.append(digest)
            else:
                malformed.append(f"certificate SHA-256 digest is not 64 lowercase hex: {digest!r}")
            continue
        if "certificate SHA-256 digest:" in line:
            malformed.append(f"unrecognised certificate digest line: {line!r}")

    if signer_count is not None:
        if signer_count < 0:
            malformed.append(f"nonsensical signer count: {signer_count}")
        elif len(set(digests)) != signer_count:
            malformed.append(
                f"apksigner reported {signer_count} signer(s) but "
                f"{len(set(digests))} distinct certificate digest(s)"
            )

    if does_not_verify and not unsigned_diagnostics and not tool_failures:
        # A bare "DOES NOT VERIFY" (or one followed by an unrecognised error)
        # is a broken/unparsable signature, never proof of being unsigned.
        for diagnostic in other_diagnostics:
            malformed.append(f"signature verification failed for an unrecognised reason: {diagnostic}")

    return ApkSignature(
        verifies=verifies,
        schemes=schemes,
        signer_count=signer_count,
        cert_sha256=tuple(digests),
        malformed=tuple(malformed),
        does_not_verify=does_not_verify,
        unsigned_diagnostics=tuple(unsigned_diagnostics),
        other_diagnostics=tuple(other_diagnostics),
        tool_failures=tuple(dict.fromkeys(tool_failures)),
    )


# ---------------------------------------------------------------------------
# ZIP container signature evidence
# ---------------------------------------------------------------------------

_EOCD_SIGNATURE = b"PK\x05\x06"
_LOCAL_HEADER_SIGNATURE = b"PK\x03\x04"
_EOCD_MIN_SIZE = 22


def _jar_signature_entries(names: Iterable[str]) -> tuple[str, ...]:
    """Return META-INF entries that constitute a JAR (v1) signature."""

    hits: list[str] = []
    for name in names:
        upper = name.upper()
        if not upper.startswith("META-INF/"):
            continue
        if upper.endswith(JAR_SIGNATURE_ENTRY_SUFFIXES):
            hits.append(name)
    return tuple(sorted(hits))


def scan_zip_signature(apk: str | os.PathLike[str]) -> ZipSignatureEvidence:
    """Inspect the ZIP container without relying on apksigner's wording.

    Detects a JAR (v1) signature by its ``META-INF`` entries and a v2/v3
    signature by the ``APK Sig Block 42`` magic that must sit immediately
    before the central directory.  Scheme v4 is not part of the APK container
    (it is an external ``.idsig`` file), so the ZIP scan neither sees nor needs
    to see it.  Any other bytes between the last local entry and the central
    directory are reported as an error: an APK that cannot be explained is not
    an APK this tool is willing to call unsigned.
    """

    path = Path(apk)
    errors: list[str] = []
    jar_entries: tuple[str, ...] = ()
    signing_block = False
    trailing_bytes = 0

    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            infos = archive.infolist()
    except (zipfile.BadZipFile, OSError) as error:
        return ZipSignatureEvidence(
            jar_signature_entries=(),
            signing_block=False,
            trailing_bytes=0,
            errors=(f"cannot read {path.name} as a ZIP archive: {error}",),
        )

    jar_entries = _jar_signature_entries(names)
    size = path.stat().st_size

    try:
        with open(path, "rb") as handle:
            last_entry_end = 0
            for info in infos:
                handle.seek(info.header_offset)
                header = handle.read(30)
                if len(header) < 30 or header[:4] != _LOCAL_HEADER_SIGNATURE:
                    errors.append(f"local file header for {info.filename!r} is malformed")
                    continue
                name_len = int.from_bytes(header[26:28], "little")
                extra_len = int.from_bytes(header[28:30], "little")
                end = info.header_offset + 30 + name_len + extra_len + info.compress_size
                last_entry_end = max(last_entry_end, end)

            tail_len = min(size, 65535 + _EOCD_MIN_SIZE)
            handle.seek(size - tail_len)
            tail = handle.read(tail_len)
            index = tail.rfind(_EOCD_SIGNATURE)
            if index < 0 or index + _EOCD_MIN_SIZE > len(tail):
                errors.append("ZIP end-of-central-directory record not found")
            elif not errors:
                cd_offset = int.from_bytes(tail[index + 16 : index + 20], "little")
                gap = cd_offset - last_entry_end
                if gap < 0:
                    errors.append(
                        f"central directory at {cd_offset} overlaps the last ZIP entry "
                        f"ending at {last_entry_end}"
                    )
                elif gap:
                    trailing_bytes = gap
                    if gap < len(APK_SIGNING_BLOCK_MAGIC):
                        errors.append(
                            f"unrecognised {gap} trailing byte(s) before the central directory"
                        )
                    else:
                        handle.seek(cd_offset - len(APK_SIGNING_BLOCK_MAGIC))
                        magic = handle.read(len(APK_SIGNING_BLOCK_MAGIC))
                        if magic == APK_SIGNING_BLOCK_MAGIC:
                            signing_block = True
                        else:
                            errors.append(
                                f"unrecognised {gap} trailing byte(s) before the central "
                                "directory: neither an APK Signing Block nor padding"
                            )
    except OSError as error:
        errors.append(f"cannot read {path.name}: {error}")

    return ZipSignatureEvidence(
        jar_signature_entries=jar_entries,
        signing_block=signing_block,
        trailing_bytes=trailing_bytes,
        errors=tuple(errors),
    )


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------


def _abi_errors(meta: ApkMetadata, expected_abis: Sequence[str]) -> list[str]:
    expected = tuple(expected_abis)
    if meta.abis == expected:
        return []
    return [
        "unexpected native ABIs: expected exactly "
        f"{list(expected)} but found {list(meta.abis)}"
    ]


def _signature_presence_errors(sig: ApkSignature) -> list[str]:
    return list(sig.malformed)


def _zip_errors(zip_evidence: ZipSignatureEvidence | None) -> list[str]:
    if zip_evidence is None:
        return []
    return list(zip_evidence.errors)


def _unsigned_evidence_errors(
    sig: ApkSignature,
    zip_evidence: ZipSignatureEvidence | None,
) -> list[str]:
    """Every reason the candidate cannot be *proved* unsigned.

    The CLI accepts an unsigned candidate only when both the apksigner verdict
    and the ZIP container independently agree, so a JVM launch failure, an
    empty/truncated output, a broken signature or an unparsable APK can never
    slip through as "unsigned".
    """

    errors: list[str] = list(sig.malformed)
    if sig.tool_failures:
        errors.append(
            "apksigner never evaluated the APK (tool/launch/parse failure), so it "
            "cannot be called unsigned: " + " | ".join(sig.tool_failures)
        )
    if not sig.verifies:
        if not sig.does_not_verify:
            errors.append(
                "apksigner printed neither 'Verifies' nor 'DOES NOT VERIFY' "
                "(exit status alone is not evidence): cannot prove the APK is unsigned"
            )
        elif not sig.unsigned_diagnostics:
            detail = "; ".join(sig.other_diagnostics) or "no diagnostic at all"
            errors.append(
                "apksigner said DOES NOT VERIFY, but not for a recognised unsigned "
                f"reason (expected exactly one of {list(UNSIGNED_DIAGNOSTICS)}): {detail}"
            )

    if zip_evidence is None:
        errors.append(
            "no ZIP-level signature evidence was collected: refusing to call the "
            "candidate unsigned on apksigner output alone"
        )
    else:
        errors.extend(zip_evidence.errors)
        if zip_evidence.jar_signature_entries:
            errors.append(
                "APK carries META-INF JAR (v1) signature entries, so it is not unsigned: "
                + ", ".join(zip_evidence.jar_signature_entries)
            )
        if zip_evidence.signing_block:
            errors.append(
                "APK carries an APK Signing Block (v2/v3/v4 signature), so it is not unsigned"
            )
    return errors


def validate_coexist(
    meta: ApkMetadata,
    sig: ApkSignature,
    expected_abis: Sequence[str] = DEFAULT_ABIS,
    expected_label: str = COEXIST_LABEL,
    expected_cert_sha256: str | None = SHARED_DEBUG_CERT_SHA256,
    zip_evidence: ZipSignatureEvidence | None = None,
) -> list[str]:
    """Validate the coexisting (installed side by side) APK."""

    errors: list[str] = []
    if meta.package != COEXIST_PACKAGE:
        errors.append(
            f"coexist package must be {COEXIST_PACKAGE!r}, found {meta.package!r}"
        )
    if meta.package == PRODUCTION_PACKAGE:
        errors.append("coexist package equals the production package: it would overwrite the release app")
    if meta.package == LIQUIDGLASS_PACKAGE:
        errors.append("coexist package equals the liquidglass debug package")
    if meta.version_code < 1:
        errors.append(f"coexist versionCode must be positive, found {meta.version_code}")
    errors.extend(_abi_errors(meta, expected_abis))
    if meta.label != expected_label:
        errors.append(
            f"coexist launcher label must be {expected_label!r}, found {meta.label!r}"
        )
    errors.extend(_signature_presence_errors(sig))
    errors.extend(_zip_errors(zip_evidence))
    if not sig.verifies:
        errors.append("coexist APK must be signed and verifiable (shared debug key)")
    certs = sig.unique_certs
    if len(certs) != 1:
        errors.append(
            f"coexist APK must have exactly one signer certificate, found {len(certs)}"
        )
    if PRODUCTION_CERT_SHA256 in certs:
        errors.append("coexist APK is signed with the production certificate; refusing")
    if expected_cert_sha256 is not None:
        if len(certs) == 1 and certs[0] != expected_cert_sha256:
            errors.append(
                "coexist signer certificate does not match the shared debug certificate: "
                f"expected {expected_cert_sha256}, found {certs[0]}"
            )
    if sig.schemes and not (sig.schemes.get("v2") or sig.schemes.get("v3") or sig.schemes.get("v3.1")):
        errors.append("coexist APK is not signed with APK Signature Scheme v2/v3")
    return errors


def validate_update_candidate(
    meta: ApkMetadata,
    sig: ApkSignature,
    expected_version_code: int,
    expected_abis: Sequence[str] = DEFAULT_ABIS,
    zip_evidence: ZipSignatureEvidence | None = None,
) -> list[str]:
    """Validate the *unsigned* update candidate before it is signed upstream."""

    errors: list[str] = []
    if expected_version_code <= ORIGINAL_VERSION_CODE:
        errors.append(
            f"expected update versionCode must be > {ORIGINAL_VERSION_CODE}, "
            f"got {expected_version_code}"
        )
    if meta.package != PRODUCTION_PACKAGE:
        errors.append(
            f"update candidate package must be {PRODUCTION_PACKAGE!r}, found {meta.package!r}"
        )
    if meta.version_code != expected_version_code:
        errors.append(
            f"update candidate versionCode must be {expected_version_code}, "
            f"found {meta.version_code}"
        )
    if meta.version_code <= ORIGINAL_VERSION_CODE:
        errors.append(
            f"update candidate versionCode must be > {ORIGINAL_VERSION_CODE} "
            f"to install over the current release, found {meta.version_code}"
        )
    errors.extend(_abi_errors(meta, expected_abis))
    if sig.verifies:
        errors.append("update candidate must be unsigned here; it verifies as signed")
    certs = sig.unique_certs
    if certs:
        errors.append(
            "update candidate must not carry any signer certificate, "
            f"found {len(certs)} (signature: {', '.join(certs)})"
        )
    errors.extend(_unsigned_evidence_errors(sig, zip_evidence))
    return errors


def validate_signed_update(
    meta: ApkMetadata,
    sig: ApkSignature,
    expected_version_code: int,
    expected_cert_sha256: str = PRODUCTION_CERT_SHA256,
    expected_abis: Sequence[str] = DEFAULT_ABIS,
    zip_evidence: ZipSignatureEvidence | None = None,
) -> list[str]:
    """Validate an update APK signed by the original production certificate."""

    errors: list[str] = []
    if expected_version_code <= ORIGINAL_VERSION_CODE:
        errors.append(
            f"expected update versionCode must be > {ORIGINAL_VERSION_CODE}, "
            f"got {expected_version_code}"
        )
    if meta.package != PRODUCTION_PACKAGE:
        errors.append(
            f"signed update package must be {PRODUCTION_PACKAGE!r}, found {meta.package!r}"
        )
    if meta.version_code != expected_version_code:
        errors.append(
            f"signed update versionCode must be {expected_version_code}, "
            f"found {meta.version_code}"
        )
    if meta.version_code <= ORIGINAL_VERSION_CODE:
        errors.append(
            f"signed update versionCode must be > {ORIGINAL_VERSION_CODE}, "
            f"found {meta.version_code}"
        )
    errors.extend(_abi_errors(meta, expected_abis))
    errors.extend(_signature_presence_errors(sig))
    errors.extend(_zip_errors(zip_evidence))
    if not sig.verifies:
        errors.append("signed update APK does not verify")
    certs = sig.unique_certs
    if len(certs) != 1:
        errors.append(f"signed update must have exactly one signer certificate, found {len(certs)}")
    elif certs[0] != expected_cert_sha256:
        errors.append(
            "signer certificate mismatch: this is NOT the original production key "
            f"(expected {expected_cert_sha256}, found {certs[0]}); refusing to label it an update"
        )
    if sig.schemes and not (sig.schemes.get("v2") or sig.schemes.get("v3") or sig.schemes.get("v3.1")):
        errors.append("signed update is not signed with APK Signature Scheme v2/v3")
    return errors


# ---------------------------------------------------------------------------
# BuildConfig consistency
# ---------------------------------------------------------------------------

_BUILD_CONFIG_FIELD_RE = re.compile(
    r"\b(?P<name>VERSION_CODE|VERSION_NAME)\s*=\s*\"(?P<value>[^\"]*)\""
)


def parse_build_config(text: str) -> dict[str, str]:
    """Extract VERSION_CODE / VERSION_NAME string constants from BuildConfig."""

    values: dict[str, str] = {}
    for match in _BUILD_CONFIG_FIELD_RE.finditer(text):
        values[match.group("name")] = match.group("value")
    return values


def build_config_errors(
    text: str,
    expected_version_code: int,
    expected_version_name: str | None = None,
) -> list[str]:
    values = parse_build_config(text)
    errors: list[str] = []
    if "VERSION_CODE" not in values:
        errors.append("BuildConfig has no VERSION_CODE string constant")
    elif values["VERSION_CODE"] != str(expected_version_code):
        errors.append(
            f"BuildConfig.VERSION_CODE is {values['VERSION_CODE']!r} but the package "
            f"metadata expects {str(expected_version_code)!r}"
        )
    if expected_version_name is not None:
        if "VERSION_NAME" not in values:
            errors.append("BuildConfig has no VERSION_NAME string constant")
        elif values["VERSION_NAME"] != expected_version_name:
            errors.append(
                f"BuildConfig.VERSION_NAME is {values['VERSION_NAME']!r} but "
                f"{expected_version_name!r} was expected"
            )
    return errors


# ---------------------------------------------------------------------------
# Tool discovery / process execution
# ---------------------------------------------------------------------------


class ToolError(RuntimeError):
    """Raised when a required SDK tool cannot be located or run."""


def _version_key(path: Path) -> tuple[int, ...]:
    match = re.search(r"([0-9]+(?:\.[0-9]+)*)", path.name)
    if not match:
        return (0,)
    return tuple(int(part) for part in match.group(1).split("."))


def resolve_tool(name: str, explicit: str | None, sdk_root: str | None) -> str:
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    else:
        if sdk_root:
            build_tools = Path(sdk_root) / "build-tools"
            if build_tools.is_dir():
                for directory in sorted(build_tools.iterdir(), key=_version_key, reverse=True):
                    if not directory.is_dir():
                        continue
                    for suffix in ("", ".exe", ".bat", ".cmd"):
                        candidate = directory / f"{name}{suffix}"
                        if candidate.is_file():
                            candidates.append(candidate)
                            break
        found = shutil.which(name)
        if found:
            candidates.append(Path(found))

    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise ToolError(
        f"cannot find {name!r}; pass it explicitly or set ANDROID_HOME/ANDROID_SDK_ROOT"
    )


def _command_for(tool_path: str, args: Sequence[str]) -> list[str]:
    if os.name == "nt" and tool_path.lower().endswith((".bat", ".cmd")):
        return ["cmd.exe", "/c", tool_path, *args]
    return [tool_path, *args]


def run_tool(tool_path: str, args: Sequence[str]) -> tuple[int, str]:
    command = _command_for(tool_path, args)
    completed = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    return completed.returncode, completed.stdout


def sha256_file(path: str | os.PathLike[str], chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            chunk = handle.read(chunk_size)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


# ---------------------------------------------------------------------------
# Report rendering
# ---------------------------------------------------------------------------


def _kv(key: str, value: object) -> str:
    return f"{key}={value}"


def metadata_lines(meta: ApkMetadata) -> list[str]:
    return [
        _kv("package", meta.package),
        _kv("versionCode", meta.version_code),
        _kv("versionName", meta.version_name),
        _kv("label", meta.label if meta.label is not None else ""),
        _kv("abis", ",".join(meta.abis)),
        _kv("minSdk", meta.min_sdk if meta.min_sdk is not None else ""),
        _kv("targetSdk", meta.target_sdk if meta.target_sdk is not None else ""),
    ]


def signature_lines(sig: ApkSignature) -> list[str]:
    lines = [_kv("signed", "true" if sig.verifies else "false")]
    for scheme in sorted(sig.schemes):
        lines.append(_kv(f"scheme_{scheme}", "true" if sig.schemes[scheme] else "false"))
    lines.append(_kv("signerCount", sig.signer_count if sig.signer_count is not None else ""))
    for index, digest in enumerate(sig.unique_certs, start=1):
        lines.append(_kv(f"certSha256_{index}", digest))
    lines.append(_kv("doesNotVerify", "true" if sig.does_not_verify else "false"))
    lines.append(_kv("unsignedEvidence", "true" if sig.unsigned_evidence else "false"))
    for diagnostic in sig.unsigned_diagnostics:
        lines.append(_kv("unsignedDiagnostic", diagnostic))
    return lines


def zip_signature_lines(evidence: ZipSignatureEvidence) -> list[str]:
    return [
        _kv("jarSignatureEntries", ",".join(evidence.jar_signature_entries) or "none"),
        _kv("apkSigningBlock", "true" if evidence.signing_block else "false"),
        _kv("trailingBytes", evidence.trailing_bytes),
        _kv("unsignedByZip", "true" if evidence.unsigned_evidence else "false"),
    ]


@dataclass(frozen=True)
class ApkInspection:
    """Everything the tool learned about one APK."""

    meta: ApkMetadata
    signature: ApkSignature
    zip_signature: ZipSignatureEvidence

    def lines(self) -> list[str]:
        return (
            metadata_lines(self.meta)
            + signature_lines(self.signature)
            + zip_signature_lines(self.zip_signature)
        )

    def unresolved_errors(self) -> list[str]:
        """Hard errors that make even a bare inspection report untrustworthy."""

        errors = list(self.signature.malformed)
        errors.extend(self.signature.tool_failures)
        errors.extend(self.zip_signature.errors)
        return errors


def inspect_apk(apk: str, aapt2: str, apksigner: str) -> ApkInspection:
    code, badging = run_tool(aapt2, ["dump", "badging", apk])
    if code != 0:
        raise ToolError(f"aapt2 dump badging failed (exit {code}):\n{badging}")
    meta = parse_badging(badging)

    code, certs = run_tool(apksigner, ["verify", "--verbose", "--print-certs", apk])
    if code not in (0, 1):
        # apksigner returns 1 for "does not verify"; anything else is a tool failure.
        raise ToolError(f"apksigner verify failed to run (exit {code}):\n{certs}")
    sig = parse_apksigner(certs)
    if sig.tool_failures:
        # A JVM launch failure or an unparsable APK also exits 1 with no
        # certificates; it must never be mistaken for an unsigned APK.
        raise ToolError(
            "apksigner never evaluated the APK:\n" + "\n".join(sig.tool_failures)
        )
    if code == 1 and not sig.does_not_verify:
        raise ToolError(
            "apksigner exited 1 without a 'DOES NOT VERIFY' verdict; refusing to "
            f"guess the signature state:\n{certs}"
        )
    if code == 0 and not sig.verifies:
        raise ToolError(
            f"apksigner exited 0 but reported no verification:\n{certs}"
        )
    return ApkInspection(meta=meta, signature=sig, zip_signature=scan_zip_signature(apk))


def _emit(lines: Iterable[str]) -> None:
    for line in lines:
        print(line)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _add_tool_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--aapt2", default=os.environ.get("AAPT2"), help="path to aapt2")
    parser.add_argument("--apksigner", default=os.environ.get("APKSIGNER"), help="path to apksigner")
    parser.add_argument(
        "--sdk-root",
        default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"),
        help="Android SDK root used to locate aapt2/apksigner",
    )
    parser.add_argument("--expected-abis", default=",".join(DEFAULT_ABIS))


def _abis(raw: str) -> tuple[str, ...]:
    return tuple(part.strip() for part in raw.split(",") if part.strip())


def _load(apk: str, args: argparse.Namespace) -> ApkInspection:
    if not os.path.isfile(apk):
        raise ToolError(f"APK not found: {apk}")
    aapt2 = resolve_tool("aapt2", args.aapt2, args.sdk_root)
    apksigner = resolve_tool("apksigner", args.apksigner, args.sdk_root)
    return inspect_apk(apk, aapt2, apksigner)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    subparsers = parser.add_subparsers(dest="command", required=True)

    inspect_parser = subparsers.add_parser("inspect", help="print public identity metadata")
    inspect_parser.add_argument("--apk", required=True)
    _add_tool_options(inspect_parser)

    coexist_parser = subparsers.add_parser("verify-coexist", help="validate the coexist APK")
    coexist_parser.add_argument("--apk", required=True)
    coexist_parser.add_argument("--expected-label", default=COEXIST_LABEL)
    coexist_parser.add_argument(
        "--allow-any-signer",
        action="store_true",
        help="do not require the shared debug certificate (still forbids the production cert)",
    )
    _add_tool_options(coexist_parser)

    candidate_parser = subparsers.add_parser(
        "verify-update-candidate", help="validate the unsigned update candidate"
    )
    candidate_parser.add_argument("--apk", required=True)
    candidate_parser.add_argument("--expected-version-code", required=True, type=int)
    _add_tool_options(candidate_parser)

    signed_parser = subparsers.add_parser(
        "verify-signed-update", help="validate an update APK signed by the original key"
    )
    signed_parser.add_argument("--apk", required=True)
    signed_parser.add_argument("--expected-version-code", required=True, type=int)
    signed_parser.add_argument("--expected-cert-sha256", default=PRODUCTION_CERT_SHA256)
    _add_tool_options(signed_parser)

    build_config_parser = subparsers.add_parser(
        "check-buildconfig", help="check generated BuildConfig against APK metadata"
    )
    build_config_parser.add_argument("--file", required=True)
    build_config_parser.add_argument("--expected-version-code", required=True, type=int)
    build_config_parser.add_argument("--expected-version-name", default=None)

    args = parser.parse_args(argv)

    try:
        if args.command == "inspect":
            inspection = _load(args.apk, args)
            _emit(
                [f"apk={args.apk}", f"apkSha256={sha256_file(args.apk)}"]
                + inspection.lines()
            )
            unresolved = inspection.unresolved_errors()
            if unresolved:
                _emit(f"ERROR: {error}" for error in unresolved)
                return 1
            return 0

        if args.command == "check-buildconfig":
            path = Path(args.file)
            files = [path]
            if path.is_dir():
                files = sorted(path.rglob("BuildConfig.java"))
            texts: list[str] = []
            for candidate in files:
                if not candidate.is_file():
                    raise ToolError(f"BuildConfig not found: {candidate}")
                texts.append(candidate.read_text(encoding="utf-8", errors="replace"))
            if not texts:
                raise ToolError(f"BuildConfig not found under: {path}")
            errors: list[str] = []
            for text in texts:
                errors.extend(
                    build_config_errors(text, args.expected_version_code, args.expected_version_name)
                )
            if errors:
                _emit(f"ERROR: {error}" for error in errors)
                return 1
            _emit(
                [
                    f"buildConfigFile={files[0]}",
                    f"versionCode={args.expected_version_code}",
                    f"versionName={args.expected_version_name or ''}",
                ]
            )
            return 0

        inspection = _load(args.apk, args)
        meta = inspection.meta
        sig = inspection.signature
        zip_evidence = inspection.zip_signature
        abis = _abis(args.expected_abis)

        if args.command == "verify-coexist":
            errors = validate_coexist(
                meta,
                sig,
                expected_abis=abis,
                expected_label=args.expected_label,
                expected_cert_sha256=None if args.allow_any_signer else SHARED_DEBUG_CERT_SHA256,
                zip_evidence=zip_evidence,
            )
        elif args.command == "verify-update-candidate":
            errors = validate_update_candidate(
                meta, sig, args.expected_version_code, expected_abis=abis, zip_evidence=zip_evidence
            )
        elif args.command == "verify-signed-update":
            errors = validate_signed_update(
                meta,
                sig,
                args.expected_version_code,
                expected_cert_sha256=args.expected_cert_sha256,
                expected_abis=abis,
                zip_evidence=zip_evidence,
            )
        else:  # pragma: no cover - argparse prevents this
            raise ToolError(f"unknown command: {args.command}")

        _emit(
            [f"apk={args.apk}", f"apkSha256={sha256_file(args.apk)}"]
            + inspection.lines()
        )
        if errors:
            _emit(f"ERROR: {error}" for error in errors)
            return 1
        print("OK")
        return 0
    except (ToolError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
