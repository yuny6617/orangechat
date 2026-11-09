"""Unit tests for tools/apk_identity.py (package/version/ABI/signature checks).

These tests are hermetic: they feed captured `aapt2`/`apksigner` output into the
pure parsers and validators, so they need no Android SDK and cannot touch a real
device or APK.
"""

import os
import tempfile
import unittest
import zipfile

from apk_identity import (
    APK_SIGNING_BLOCK_MAGIC,
    COEXIST_LABEL,
    COEXIST_PACKAGE,
    DEFAULT_ABIS,
    LIQUIDGLASS_PACKAGE,
    ORIGINAL_VERSION_CODE,
    PRODUCTION_CERT_SHA256,
    PRODUCTION_PACKAGE,
    SHARED_DEBUG_CERT_SHA256,
    UNSIGNED_DIAGNOSTICS,
    UPDATE_MIN_VERSION_CODE,
    ZipSignatureEvidence,
    build_config_errors,
    metadata_lines,
    parse_apksigner,
    parse_badging,
    parse_build_config,
    scan_zip_signature,
    sha256_file,
    signature_lines,
    validate_coexist,
    validate_signed_update,
    validate_update_candidate,
)

# Captured from `aapt2 dump badging` of the delivered production APK
# (OrangeChat-2.2.9-WorkspaceDual-20261108-arm64-release.apk, versionCode 165).
BADGING_PRODUCTION = (
    "package: name='me.rerere.orangechat' versionCode='165' versionName='2.2.9' "
    "platformBuildVersionName='17' platformBuildVersionCode='37' "
    "compileSdkVersion='37' compileSdkVersionCodename='17'\n"
    "sdkVersion:'26'\n"
    "minSdkVersion:'26'\n"
    "targetSdkVersion:'37'\n"
    "uses-permission: name='android.permission.INTERNET'\n"
    "application-label:'西瓜瓣'\n"
    "application-label-en:'西瓜瓣'\n"
    "application-label-zh:'西瓜瓣'\n"
    "application: label='西瓜瓣' icon='res/IG.xml'\n"
    "launchable-activity: name='me.rerere.rikkahub.RouteActivity'  label='' icon=''\n"
    "native-code: 'arm64-v8a'\n"
)

BADGING_COEXIST = (
    "package: name='me.rerere.orangechat.videogifts' versionCode='165' "
    "versionName='2.2.9' platformBuildVersionName='17' platformBuildVersionCode='37'\n"
    "minSdkVersion:'26'\n"
    "targetSdkVersion:'37'\n"
    "application-label:'西瓜瓣·视频共存'\n"
    "application-label-en:'西瓜瓣·视频共存'\n"
    "application: label='西瓜瓣·视频共存' icon='res/IG.xml'\n"
    "native-code: 'arm64-v8a'\n"
)

BADGING_UPDATE_166 = (
    "package: name='me.rerere.orangechat' versionCode='166' versionName='2.2.9' "
    "platformBuildVersionName='17' platformBuildVersionCode='37'\n"
    "minSdkVersion:'26'\n"
    "targetSdkVersion:'37'\n"
    "application-label:'西瓜瓣'\n"
    "native-code: 'arm64-v8a'\n"
)

# Captured from `apksigner verify --verbose --print-certs` of the delivered APK.
APKSIGNER_SINGLE = """\
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Verified using v4 scheme (APK Signature Scheme v4): false
Number of signers: 1
V2 Signer: certificate DN: CN=OrangeChat, OU=Dev, O=Dev, L=City, ST=State, C=CN
V2 Signer: certificate SHA-256 digest: 49ceec2c9d193a1b515ff7c5aa3a8bb745e2dbcfdb8eb0b99aa184b88472ae64
V2 Signer: certificate SHA-1 digest: 5e46b9a59614744b49e5a5b25bbc0d3dd97b3436
V2 Signer: certificate MD5 digest: 4dc076b6114c79fbda12a02c5c051b86
V2 Signer: key algorithm: RSA
V2 Signer: key size (bits): 2048
"""

APKSIGNER_DEBUG = """\
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
V1 Signer: certificate SHA-256 digest: ff516315e577235b64ff356dbc856c053b7e589ae636dd8bc4301e528b46d116
V2 Signer: certificate SHA-256 digest: ff516315e577235b64ff356dbc856c053b7e589ae636dd8bc4301e528b46d116
V3 Signer: certificate SHA-256 digest: ff516315e577235b64ff356dbc856c053b7e589ae636dd8bc4301e528b46d116
"""

APKSIGNER_MULTI = """\
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 2
Signer #1 certificate SHA-256 digest: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
Signer #2 certificate SHA-256 digest: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
"""

APKSIGNER_UNSIGNED = """\
DOES NOT VERIFY
ERROR: Missing META-INF/MANIFEST.MF
"""

# Everything below is a *captured or reproduced* failure mode that also exits 1
# and prints no certificates.  None of them may be read as "unsigned".
APKSIGNER_EMPTY = ""

APKSIGNER_JAVA_LAUNCH_FAILURE = """\
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation.
"""

APKSIGNER_JVM_CRASH = """\
Exception in thread "main" com.android.apksig.apk.ApkFormatException: Malformed APK: not a ZIP archive
\tat com.android.apksig.ApkVerifier.verify(ApkVerifier.java:193)
\tat com.android.apksigner.ApkSignerTool.verify(ApkSignerTool.java:678)
Caused by: com.android.apksig.zip.ZipFormatException: ZIP End of Central Directory record not found
\tat com.android.apksig.internal.zip.ZipUtils.findZipSections(ZipUtils.java:278)
"""

APKSIGNER_BROKEN_SIGNATURE = """\
DOES NOT VERIFY
ERROR: APK Signature Scheme v3 signer #1: APK integrity check failed. CHUNKED_SHA256 digest mismatch. Expected: <a097287c117f34d89aef3503809305917bcfc210575d5010ec26a3232b89d0f7>, actual: <50c8d09e8e7ffd8e2ef3d45e11f8e93a7c019d85f677024737b9ce2568b04ef0>
"""

# Recognisable shape, unrecognised diagnostic: not in the unsigned allow-list.
APKSIGNER_UNRECOGNISED_DIAGNOSTIC = """\
DOES NOT VERIFY
ERROR: Missing AndroidManifest.xml
"""

# A hostile / garbled digest must never be compared or accepted.
APKSIGNER_MALICIOUS_CERT = """\
Verifies
Number of signers: 1
V2 Signer: certificate SHA-256 digest: 49ceec2c9d193a1b515ff7c5aa3a8bb745e2dbcfdb8eb0b99aa184b88472ae64; rm -rf /
"""

APKSIGNER_UPPERCASE_CERT = """\
Verifies
Number of signers: 1
V2 Signer: certificate SHA-256 digest: 49CEEC2C9D193A1B515FF7C5AA3A8BB745E2DBCFDB8EB0B99AA184B88472AE64
"""

# ZIP-level evidence (independent of apksigner).
ZIP_UNSIGNED = ZipSignatureEvidence(jar_signature_entries=(), signing_block=False, trailing_bytes=0, errors=())
ZIP_V1_SIGNED = ZipSignatureEvidence(
    jar_signature_entries=("META-INF/CERT.RSA", "META-INF/CERT.SF", "META-INF/MANIFEST.MF"),
    signing_block=False,
    trailing_bytes=0,
    errors=(),
)
ZIP_V2_SIGNED = ZipSignatureEvidence(
    jar_signature_entries=(), signing_block=True, trailing_bytes=4096, errors=()
)
ZIP_BROKEN = ZipSignatureEvidence(
    jar_signature_entries=(),
    signing_block=False,
    trailing_bytes=0,
    errors=("ZIP end-of-central-directory record not found",),
)

APKSIGNER_TRUNCATED = """\
Verifies
Number of signers: 1
V2 Signer: certificate SHA-256 digest: 49ceec2c9d193a1b
"""

APKSIGNER_NO_CERT = """\
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
"""

APKSIGNER_MISMATCHED_PER_SCHEME = """\
Verifies
Number of signers: 2
V2 Signer: certificate SHA-256 digest: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
"""


def production_meta(version_code=165):
    return parse_badging(
        f"package: name='{PRODUCTION_PACKAGE}' versionCode='{version_code}' versionName='2.2.9'\n"
        "minSdkVersion:'26'\n"
        "targetSdkVersion:'37'\n"
        "application-label:'西瓜瓣'\n"
        "native-code: 'arm64-v8a'\n"
    )


class BadgingParsingTest(unittest.TestCase):
    def test_parses_production_metadata(self):
        meta = parse_badging(BADGING_PRODUCTION)
        self.assertEqual(meta.package, "me.rerere.orangechat")
        self.assertEqual(meta.version_code, 165)
        self.assertEqual(meta.version_name, "2.2.9")
        self.assertEqual(meta.label, "西瓜瓣")
        self.assertEqual(meta.abis, ("arm64-v8a",))
        self.assertEqual(meta.min_sdk, 26)
        self.assertEqual(meta.target_sdk, 37)

    def test_parses_coexist_metadata_and_label(self):
        meta = parse_badging(BADGING_COEXIST)
        self.assertEqual(meta.package, COEXIST_PACKAGE)
        self.assertEqual(meta.label, COEXIST_LABEL)

    def test_missing_package_line_is_an_error(self):
        with self.assertRaises(ValueError):
            parse_badging("application-label:'x'\n")

    def test_missing_version_code_is_an_error(self):
        with self.assertRaises(ValueError):
            parse_badging("package: name='me.rerere.orangechat' versionName='2.2.9'\n")

    def test_takes_only_the_default_label(self):
        meta = parse_badging(BADGING_PRODUCTION)
        # A localised label must not override the unqualified label.
        self.assertEqual(meta.label, "西瓜瓣")

    def test_reports_multiple_abis(self):
        meta = parse_badging(
            "package: name='me.rerere.orangechat' versionCode='165' versionName='2.2.9'\n"
            "native-code: 'arm64-v8a' 'x86_64'\n"
        )
        self.assertEqual(meta.abis, ("arm64-v8a", "x86_64"))


class ApksignerParsingTest(unittest.TestCase):
    def test_single_signer_v2(self):
        sig = parse_apksigner(APKSIGNER_SINGLE)
        self.assertTrue(sig.verifies)
        self.assertEqual(sig.signer_count, 1)
        self.assertEqual(sig.cert_sha256, (PRODUCTION_CERT_SHA256,))
        self.assertTrue(sig.schemes["v2"])
        self.assertFalse(sig.schemes["v1"])
        self.assertEqual(sig.unique_certs, (PRODUCTION_CERT_SHA256,))

    def test_repeated_certificate_across_schemes_is_one_unique_signer(self):
        sig = parse_apksigner(APKSIGNER_DEBUG)
        self.assertEqual(len(sig.cert_sha256), 3)
        self.assertEqual(sig.unique_certs, (SHARED_DEBUG_CERT_SHA256,))

    def test_multiple_signers_are_parsed(self):
        sig = parse_apksigner(APKSIGNER_MULTI)
        self.assertEqual(sig.signer_count, 2)
        self.assertEqual(len(sig.unique_certs), 2)
        self.assertFalse(sig.malformed)

    def test_unsigned_apk_is_not_verified(self):
        sig = parse_apksigner(APKSIGNER_UNSIGNED)
        self.assertFalse(sig.verifies)
        self.assertTrue(sig.does_not_verify)
        self.assertTrue(sig.unsigned_evidence)
        self.assertEqual(sig.unique_certs, ())

    def test_signer_count_zero_is_parsed(self):
        sig = parse_apksigner(APKSIGNER_UNSIGNED + "Number of signers: 0\n")
        self.assertFalse(sig.verifies)
        self.assertEqual(sig.signer_count, 0)
        self.assertTrue(sig.unsigned_evidence)

    def test_bare_does_not_verify_is_not_unsigned_evidence(self):
        sig = parse_apksigner("DOES NOT VERIFY\n")
        self.assertTrue(sig.does_not_verify)
        self.assertFalse(sig.unsigned_evidence)

    def test_truncated_digest_is_malformed(self):
        sig = parse_apksigner(APKSIGNER_TRUNCATED)
        self.assertTrue(sig.malformed)
        self.assertEqual(sig.unique_certs, ())

    def test_missing_certificate_lines_are_detected(self):
        sig = parse_apksigner(APKSIGNER_NO_CERT)
        self.assertTrue(sig.verifies)
        self.assertEqual(sig.unique_certs, ())

    def test_signer_count_mismatch_is_malformed(self):
        sig = parse_apksigner(APKSIGNER_MISMATCHED_PER_SCHEME)
        self.assertTrue(sig.malformed)

    def test_no_verification_output_means_unsigned(self):
        sig = parse_apksigner("")
        self.assertFalse(sig.verifies)


class CoexistValidationTest(unittest.TestCase):
    def coexist(self, badging=BADGING_COEXIST, apksigner=APKSIGNER_DEBUG):
        return parse_badging(badging), parse_apksigner(apksigner)

    def test_valid_coexist_apk_passes(self):
        meta, sig = self.coexist()
        self.assertEqual(validate_coexist(meta, sig), [])

    def test_production_package_is_rejected(self):
        meta, sig = self.coexist(badging=BADGING_PRODUCTION)
        errors = validate_coexist(meta, sig)
        self.assertTrue(any("coexist package must be" in error for error in errors))
        self.assertTrue(any("production package" in error for error in errors))

    def test_liquidglass_package_is_rejected(self):
        badging = BADGING_COEXIST.replace(COEXIST_PACKAGE, LIQUIDGLASS_PACKAGE)
        meta, sig = self.coexist(badging=badging)
        errors = validate_coexist(meta, sig)
        self.assertTrue(any("liquidglass" in error for error in errors))

    def test_production_signer_is_rejected(self):
        meta, _ = self.coexist()
        sig = parse_apksigner(APKSIGNER_SINGLE)
        errors = validate_coexist(meta, sig)
        self.assertTrue(any("production certificate" in error for error in errors))
        self.assertTrue(any("shared debug certificate" in error for error in errors))

    def test_signer_mismatch_is_rejected(self):
        meta, _ = self.coexist()
        sig = parse_apksigner(
            APKSIGNER_DEBUG.replace(SHARED_DEBUG_CERT_SHA256, "c" * 64)
        )
        errors = validate_coexist(meta, sig)
        self.assertTrue(any("does not match the shared debug" in error for error in errors))

    def test_unsigned_coexist_is_rejected(self):
        meta, sig = self.coexist(apksigner=APKSIGNER_UNSIGNED)
        errors = validate_coexist(meta, sig)
        self.assertTrue(any("must be signed" in error for error in errors))

    def test_multi_signer_coexist_is_rejected(self):
        meta, sig = self.coexist(apksigner=APKSIGNER_MULTI)
        errors = validate_coexist(meta, sig)
        self.assertTrue(any("exactly one signer" in error for error in errors))

    def test_wrong_label_is_rejected(self):
        badging = BADGING_COEXIST.replace(COEXIST_LABEL, "西瓜瓣")
        meta, sig = self.coexist(badging=badging)
        errors = validate_coexist(meta, sig)
        self.assertTrue(any("launcher label" in error for error in errors))

    def test_wrong_abi_is_rejected(self):
        badging = BADGING_COEXIST.replace("'arm64-v8a'", "'x86_64'")
        meta, sig = self.coexist(badging=badging)
        errors = validate_coexist(meta, sig)
        self.assertTrue(any("ABIs" in error for error in errors))

    def test_any_signer_mode_still_forbids_production_key(self):
        meta, _ = self.coexist()
        sig = parse_apksigner(APKSIGNER_SINGLE)
        errors = validate_coexist(meta, sig, expected_cert_sha256=None)
        self.assertTrue(any("production certificate" in error for error in errors))


class UpdateCandidateValidationTest(unittest.TestCase):
    def test_unsigned_166_candidate_passes(self):
        meta = parse_badging(BADGING_UPDATE_166)
        sig = parse_apksigner(APKSIGNER_UNSIGNED)
        self.assertEqual(
            validate_update_candidate(meta, sig, 166, zip_evidence=ZIP_UNSIGNED), []
        )

    def test_version_165_is_rejected(self):
        meta = production_meta(165)
        sig = parse_apksigner(APKSIGNER_UNSIGNED)
        errors = validate_update_candidate(meta, sig, 166, zip_evidence=ZIP_UNSIGNED)
        self.assertTrue(any("must be 166" in error for error in errors))
        self.assertTrue(any("must be > 165" in error for error in errors))

    def test_expected_165_is_rejected(self):
        meta = production_meta(165)
        sig = parse_apksigner(APKSIGNER_UNSIGNED)
        errors = validate_update_candidate(meta, sig, 165, zip_evidence=ZIP_UNSIGNED)
        self.assertTrue(any("expected update versionCode must be" in error for error in errors))

    def test_signed_candidate_is_rejected(self):
        meta = parse_badging(BADGING_UPDATE_166)
        sig = parse_apksigner(APKSIGNER_SINGLE)
        errors = validate_update_candidate(meta, sig, 166, zip_evidence=ZIP_V2_SIGNED)
        self.assertTrue(any("must be unsigned" in error for error in errors))
        self.assertTrue(any("must not carry any signer" in error for error in errors))

    def test_wrong_package_is_rejected(self):
        meta = parse_badging(BADGING_UPDATE_166.replace(PRODUCTION_PACKAGE, COEXIST_PACKAGE))
        sig = parse_apksigner(APKSIGNER_UNSIGNED)
        errors = validate_update_candidate(meta, sig, 166, zip_evidence=ZIP_UNSIGNED)
        self.assertTrue(any("update candidate package must be" in error for error in errors))

    def test_multiple_abis_are_rejected(self):
        meta = parse_badging(BADGING_UPDATE_166.replace("'arm64-v8a'", "'arm64-v8a' 'x86_64'"))
        sig = parse_apksigner(APKSIGNER_UNSIGNED)
        errors = validate_update_candidate(meta, sig, 166, zip_evidence=ZIP_UNSIGNED)
        self.assertTrue(any("ABIs" in error for error in errors))

    def test_constants_stay_consistent(self):
        self.assertEqual(UPDATE_MIN_VERSION_CODE, ORIGINAL_VERSION_CODE + 1)
        self.assertEqual(DEFAULT_ABIS, ("arm64-v8a",))
        self.assertEqual(UNSIGNED_DIAGNOSTICS, ("ERROR: Missing META-INF/MANIFEST.MF",))


class UnsignedEvidenceRegressionTest(unittest.TestCase):
    """Anything that is *not* positive unsigned evidence must be rejected.

    Every case here exits 1 with no certificates, which is exactly the state
    the previous implementation silently accepted as "unsigned".
    """

    META = parse_badging(BADGING_UPDATE_166)

    def candidate_errors(self, apksigner_text, zip_evidence=ZIP_UNSIGNED):
        return validate_update_candidate(
            self.META, parse_apksigner(apksigner_text), 166, zip_evidence=zip_evidence
        )

    def assert_rejected(self, errors, needle):
        self.assertTrue(errors, "unsigned candidate must not be accepted")
        self.assertTrue(
            any(needle in error for error in errors),
            f"expected an error mentioning {needle!r}, got {errors}",
        )

    def test_exit_1_with_empty_output_is_rejected(self):
        sig = parse_apksigner(APKSIGNER_EMPTY)
        self.assertFalse(sig.unsigned_evidence)
        self.assert_rejected(self.candidate_errors(APKSIGNER_EMPTY), "neither 'Verifies' nor")

    def test_java_launch_failure_is_rejected(self):
        sig = parse_apksigner(APKSIGNER_JAVA_LAUNCH_FAILURE)
        self.assertTrue(sig.tool_failures)
        self.assertFalse(sig.unsigned_evidence)
        self.assert_rejected(
            self.candidate_errors(APKSIGNER_JAVA_LAUNCH_FAILURE), "never evaluated the APK"
        )

    def test_jvm_crash_on_unparsable_apk_is_rejected(self):
        sig = parse_apksigner(APKSIGNER_JVM_CRASH)
        self.assertTrue(sig.tool_failures)
        self.assertFalse(sig.unsigned_evidence)
        self.assert_rejected(self.candidate_errors(APKSIGNER_JVM_CRASH), "never evaluated the APK")

    def test_broken_signature_is_rejected(self):
        sig = parse_apksigner(APKSIGNER_BROKEN_SIGNATURE)
        self.assertTrue(sig.does_not_verify)
        self.assertFalse(sig.unsigned_evidence)
        self.assertTrue(sig.malformed)
        self.assert_rejected(
            self.candidate_errors(APKSIGNER_BROKEN_SIGNATURE), "DOES NOT VERIFY"
        )

    def test_unrecognised_does_not_verify_diagnostic_is_rejected(self):
        sig = parse_apksigner(APKSIGNER_UNRECOGNISED_DIAGNOSTIC)
        self.assertTrue(sig.does_not_verify)
        self.assertFalse(sig.unsigned_evidence)
        self.assert_rejected(
            self.candidate_errors(APKSIGNER_UNRECOGNISED_DIAGNOSTIC), "not for a recognised"
        )

    def test_malicious_certificate_format_is_rejected(self):
        sig = parse_apksigner(APKSIGNER_MALICIOUS_CERT)
        self.assertTrue(sig.verifies)
        self.assertFalse(sig.unsigned_evidence)
        self.assertTrue(sig.malformed)
        self.assert_rejected(self.candidate_errors(APKSIGNER_MALICIOUS_CERT), "must be unsigned")

    def test_uppercase_certificate_digest_is_rejected(self):
        sig = parse_apksigner(APKSIGNER_UPPERCASE_CERT)
        self.assertTrue(sig.malformed)
        self.assertFalse(sig.unsigned_evidence)
        self.assert_rejected(self.candidate_errors(APKSIGNER_UPPERCASE_CERT), "must be unsigned")

    def test_v1_jar_signature_in_zip_is_rejected(self):
        self.assert_rejected(
            self.candidate_errors(APKSIGNER_UNSIGNED, ZIP_V1_SIGNED), "META-INF JAR (v1)"
        )

    def test_v2_signing_block_in_zip_is_rejected(self):
        self.assert_rejected(
            self.candidate_errors(APKSIGNER_UNSIGNED, ZIP_V2_SIGNED), "APK Signing Block"
        )

    def test_unreadable_zip_is_rejected(self):
        self.assert_rejected(self.candidate_errors(APKSIGNER_UNSIGNED, ZIP_BROKEN), "end-of-central")

    def test_missing_zip_evidence_is_rejected(self):
        errors = validate_update_candidate(
            self.META, parse_apksigner(APKSIGNER_UNSIGNED), 166
        )
        self.assert_rejected(errors, "no ZIP-level signature evidence")

    def test_both_sources_must_agree(self):
        # apksigner is happy, but the container carries a signing block.
        self.assertFalse(self.candidate_errors(APKSIGNER_UNSIGNED, ZIP_V2_SIGNED) == [])
        # container is clean, but apksigner never evaluated the APK.
        self.assertFalse(self.candidate_errors(APKSIGNER_JAVA_LAUNCH_FAILURE, ZIP_UNSIGNED) == [])

    def test_valid_unsigned_candidate_has_no_errors(self):
        sig = parse_apksigner(APKSIGNER_UNSIGNED)
        self.assertTrue(sig.unsigned_evidence)
        self.assertEqual(
            validate_update_candidate(self.META, sig, 166, zip_evidence=ZIP_UNSIGNED), []
        )


class ZipSignatureScanTest(unittest.TestCase):
    """The ZIP scan must work on bytes, not on apksigner's wording."""

    def setUp(self):
        self.directory = tempfile.mkdtemp()

    def tearDown(self):
        for name in os.listdir(self.directory):
            os.unlink(os.path.join(self.directory, name))
        os.rmdir(self.directory)

    def path(self, name):
        return os.path.join(self.directory, name)

    def make_zip(self, name, entries):
        path = self.path(name)
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            for entry, payload in entries.items():
                archive.writestr(entry, payload)
        return path

    def add_trailer(self, path, trailer):
        with open(path, "rb") as handle:
            data = bytearray(handle.read())
        eocd = data.rfind(b"PK\x05\x06")
        cd_offset = int.from_bytes(data[eocd + 16 : eocd + 20], "little")
        data[cd_offset:cd_offset] = trailer
        new_offset = cd_offset + len(trailer)
        data[eocd + len(trailer) + 16 : eocd + len(trailer) + 20] = new_offset.to_bytes(4, "little")
        with open(path, "wb") as handle:
            handle.write(data)
        return path

    def test_plain_zip_is_unsigned(self):
        path = self.make_zip("plain.apk", {"AndroidManifest.xml": b"\x03\x00"})
        evidence = scan_zip_signature(path)
        self.assertEqual(evidence.jar_signature_entries, ())
        self.assertFalse(evidence.signing_block)
        self.assertEqual(evidence.trailing_bytes, 0)
        self.assertEqual(evidence.errors, ())
        self.assertTrue(evidence.unsigned_evidence)

    def test_jar_signature_entries_are_detected(self):
        path = self.make_zip(
            "v1.apk",
            {
                "AndroidManifest.xml": b"\x03\x00",
                "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n",
                "META-INF/CERT.SF": b"Signature-Version: 1.0\n",
                "META-INF/CERT.RSA": b"\x30\x82",
            },
        )
        evidence = scan_zip_signature(path)
        self.assertEqual(
            evidence.jar_signature_entries,
            ("META-INF/CERT.RSA", "META-INF/CERT.SF", "META-INF/MANIFEST.MF"),
        )
        self.assertFalse(evidence.unsigned_evidence)

    def test_meta_inf_without_signature_is_not_a_signature(self):
        path = self.make_zip(
            "meta.apk",
            {
                "AndroidManifest.xml": b"\x03\x00",
                "META-INF/com/android/build/gradle/app-metadata.properties": b"a=b\n",
                "META-INF/services/foo.Bar": b"impl\n",
            },
        )
        evidence = scan_zip_signature(path)
        self.assertEqual(evidence.jar_signature_entries, ())
        self.assertTrue(evidence.unsigned_evidence)

    def test_apk_signing_block_is_detected(self):
        path = self.make_zip("v2.apk", {"AndroidManifest.xml": b"\x03\x00"})
        block = b"\x00" * 64 + APK_SIGNING_BLOCK_MAGIC
        self.add_trailer(path, block)
        evidence = scan_zip_signature(path)
        self.assertTrue(evidence.signing_block)
        self.assertEqual(evidence.trailing_bytes, len(block))
        self.assertEqual(evidence.errors, ())
        self.assertFalse(evidence.unsigned_evidence)

    def test_unexplained_trailer_is_an_error(self):
        path = self.make_zip("garbage.apk", {"AndroidManifest.xml": b"\x03\x00"})
        self.add_trailer(path, b"\xff" * 64)
        evidence = scan_zip_signature(path)
        self.assertFalse(evidence.signing_block)
        self.assertTrue(evidence.errors)
        self.assertFalse(evidence.unsigned_evidence)

    def test_truncated_trailer_is_an_error(self):
        path = self.make_zip("short.apk", {"AndroidManifest.xml": b"\x03\x00"})
        self.add_trailer(path, b"\x00" * 8)
        evidence = scan_zip_signature(path)
        self.assertFalse(evidence.signing_block)
        self.assertTrue(evidence.errors)
        self.assertFalse(evidence.unsigned_evidence)

    def test_non_zip_file_is_an_error(self):
        path = self.path("not-a-zip.apk")
        with open(path, "wb") as handle:
            handle.write(b"definitely not a zip archive")
        evidence = scan_zip_signature(path)
        self.assertTrue(evidence.errors)
        self.assertFalse(evidence.unsigned_evidence)

    def test_missing_file_is_an_error(self):
        evidence = scan_zip_signature(self.path("does-not-exist.apk"))
        self.assertTrue(evidence.errors)
        self.assertFalse(evidence.unsigned_evidence)


class SignedUpdateValidationTest(unittest.TestCase):
    def valid(self):
        meta = parse_badging(BADGING_UPDATE_166)
        sig = parse_apksigner(APKSIGNER_SINGLE)
        return meta, sig

    def test_original_key_passes(self):
        meta, sig = self.valid()
        self.assertEqual(validate_signed_update(meta, sig, 166), [])

    def test_other_key_is_rejected(self):
        meta, _ = self.valid()
        sig = parse_apksigner(
            APKSIGNER_SINGLE.replace(PRODUCTION_CERT_SHA256, SHARED_DEBUG_CERT_SHA256)
        )
        errors = validate_signed_update(meta, sig, 166)
        self.assertTrue(any("NOT the original production key" in error for error in errors))

    def test_unsigned_is_rejected(self):
        meta, _ = self.valid()
        sig = parse_apksigner(APKSIGNER_UNSIGNED)
        errors = validate_signed_update(meta, sig, 166)
        self.assertTrue(any("does not verify" in error for error in errors))

    def test_multi_signer_is_rejected(self):
        meta, _ = self.valid()
        sig = parse_apksigner(APKSIGNER_MULTI)
        errors = validate_signed_update(meta, sig, 166)
        self.assertTrue(any("exactly one signer" in error for error in errors))

    def test_truncated_digest_is_rejected(self):
        meta, _ = self.valid()
        sig = parse_apksigner(APKSIGNER_TRUNCATED)
        errors = validate_signed_update(meta, sig, 166)
        self.assertTrue(any("64 lowercase hex" in error for error in errors))

    def test_short_version_code_is_rejected(self):
        meta = production_meta(165)
        sig = parse_apksigner(APKSIGNER_SINGLE)
        errors = validate_signed_update(meta, sig, 166)
        self.assertTrue(any("versionCode must be 166" in error for error in errors))

    def test_production_cert_constant_is_full_64_hex(self):
        self.assertTrue(len(PRODUCTION_CERT_SHA256) == 64)
        self.assertTrue(all(c in "0123456789abcdef" for c in PRODUCTION_CERT_SHA256))
        self.assertEqual(len(SHARED_DEBUG_CERT_SHA256), 64)


class BuildConfigTest(unittest.TestCase):
    SOURCE = """\
public final class BuildConfig {
  public static final boolean DEBUG = false;
  public static final String VERSION_NAME = "2.2.9";
  public static final String VERSION_CODE = "166";
  public static final String GIT_COMMIT = "4c614d6";
}
"""

    def test_parses_values(self):
        self.assertEqual(
            parse_build_config(self.SOURCE), {"VERSION_NAME": "2.2.9", "VERSION_CODE": "166"}
        )

    def test_matching_values_pass(self):
        self.assertEqual(build_config_errors(self.SOURCE, 166, "2.2.9"), [])

    def test_version_code_mismatch_is_rejected(self):
        errors = build_config_errors(self.SOURCE, 165)
        self.assertTrue(any("VERSION_CODE" in error for error in errors))

    def test_version_name_mismatch_is_rejected(self):
        errors = build_config_errors(self.SOURCE, 166, "2.2.8")
        self.assertTrue(any("VERSION_NAME" in error for error in errors))

    def test_missing_fields_are_rejected(self):
        errors = build_config_errors("public final class BuildConfig {}", 166, "2.2.9")
        self.assertTrue(any("no VERSION_CODE" in error for error in errors))
        self.assertTrue(any("no VERSION_NAME" in error for error in errors))


class ReportTest(unittest.TestCase):
    def test_metadata_lines_are_key_value(self):
        meta = parse_badging(BADGING_UPDATE_166)
        lines = metadata_lines(meta)
        self.assertIn("package=me.rerere.orangechat", lines)
        self.assertIn("versionCode=166", lines)
        self.assertIn("abis=arm64-v8a", lines)

    def test_signature_lines_include_full_digest(self):
        sig = parse_apksigner(APKSIGNER_SINGLE)
        lines = signature_lines(sig)
        self.assertIn("signed=true", lines)
        self.assertIn(f"certSha256_1={PRODUCTION_CERT_SHA256}", lines)

    def test_sha256_file_matches_known_value(self):
        payload = b"orangechat"
        with tempfile.NamedTemporaryFile(delete=False) as handle:
            handle.write(payload)
            path = handle.name
        try:
            import hashlib

            self.assertEqual(sha256_file(path), hashlib.sha256(payload).hexdigest())
        finally:
            os.unlink(path)


if __name__ == "__main__":
    unittest.main()
