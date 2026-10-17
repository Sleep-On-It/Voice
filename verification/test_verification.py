"""Self-tests of the verification harness (run: python -I verification/test_verification.py).

Layer L0/L1 style checks that need no application code:
  * comparators detect injected differences in each dimension (a comparator that cannot fail proves nothing);
  * the dumper is checked against analytic values on FX-001 (D18 3.3 common-mode safeguard: means 18.75 and
    236.25 after Invert) using an independent Python read of the raw bytes;
  * stored baselines are internally consistent (raw bytes hash to the recorded digest; the saved Tiff read back
    with Pillow equals the dumped plane).
Tests that start Fiji are skipped when the pinned installation or the lock is unavailable.
"""

from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare  # noqa: E402
import gui_smoke  # noqa: E402
import reference_runner as rr  # noqa: E402

ROOT = rr.ROOT
BASELINES = ROOT / "verification" / "baselines"


def fiji_available() -> bool:
    try:
        rr.verify_runtime(rr.fiji_executable())
        return True
    except Exception:
        return False


def sample_dump() -> dict:
    return {
        "currentImageTitle": "a.png",
        "images": [{
            "index": 0, "title": "a.png", "type": 0, "bitDepth": 8, "width": 2, "height": 2, "channels": 1,
            "slices": 1, "frames": 1, "stackSize": 1, "invertedLut": False, "lutSha256": "x",
            "displayRange": ["0.0", "255.0"], "threshold": ["-808080.0", "-808080.0"],
            "calibration": {"pixelWidth": "1.0"}, "infoSha256": None, "overlayCount": 0,
            "planeSha256": ["aa"], "allPlanesSha256": "aa",
            "roi": {"type": 0, "name": None, "bounds": [0, 0, 1, 1], "position": 0, "strokeWidth": "0.0",
                    "xs": ["0.0", "1.0"], "ys": ["0.0", "1.0"], "maskBounds": None, "maskSha256": None},
        }],
        "results": {"rows": 1, "columns": [{"name": "Area", "values": ["4.0"], "hex": ["0x1.0p2"]}]},
        "summary": None,
        "roiManager": [{"type": 2, "name": "0001", "bounds": [0, 0, 1, 1], "position": 0, "strokeWidth": "0.0",
                        "xs": ["0.5"], "ys": ["0.5"], "maskBounds": [1, 1], "maskSha256": "m"}],
        "globals": {"blackBackground": True, "precision": 9},
    }


class ComparatorTests(unittest.TestCase):
    def run_compare(self, mutate) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp, "a"), Path(tmp, "b")
            a.mkdir()
            b.mkdir()
            ref = sample_dump()
            cand = copy.deepcopy(ref)
            mutate(cand)
            (a / "s01.json").write_text(json.dumps(ref))
            (b / "s01.json").write_text(json.dumps(cand))
            return compare.compare(a, b)

    def test_identical_passes_every_dimension(self):
        report = self.run_compare(lambda d: None)
        self.assertTrue(report["ok"], report)

    def test_e1_detects_a_pixel_difference(self):
        def mutate(d):
            d["images"][0]["planeSha256"] = ["bb"]
            d["images"][0]["allPlanesSha256"] = "bb"
        report = self.run_compare(mutate)
        self.assertEqual(report["steps"]["s01"]["E1"]["status"], "fail")
        self.assertFalse(report["ok"])

    def test_e3_detects_calibration_and_lut(self):
        def mutate(d):
            d["images"][0]["calibration"]["pixelWidth"] = "1.0000000000000002"
        self.assertEqual(self.run_compare(mutate)["steps"]["s01"]["E3"]["status"], "fail")

    def test_e4_detects_a_subpixel_vertex(self):
        def mutate(d):
            d["roiManager"][0]["xs"] = ["0.5000000000000001"]
        self.assertEqual(self.run_compare(mutate)["steps"]["s01"]["E4"]["status"], "fail")

    def test_e5_detects_last_bit_difference(self):
        def mutate(d):
            d["results"]["columns"][0]["values"] = ["4.000000000000001"]
            d["results"]["columns"][0]["hex"] = ["0x1.0000000000001p2"]
        self.assertEqual(self.run_compare(mutate)["steps"]["s01"]["E5"]["status"], "fail")

    def test_e6_detects_a_global_change(self):
        def mutate(d):
            d["globals"]["blackBackground"] = False
        self.assertEqual(self.run_compare(mutate)["steps"]["s01"]["E6"]["status"], "fail")

    def test_missing_roi_manager_is_not_a_pass(self):
        def mutate(d):
            d["roiManager"] = None
        self.assertEqual(self.run_compare(mutate)["steps"]["s01"]["E4"]["status"], "pass-partial")


class GuiSmokeCheckerTests(unittest.TestCase):
    """The blank-screenshot detector must reject blank images and accept a rendered-looking one."""

    def make(self, tmp: Path, name: str, draw) -> Path:
        from PIL import Image, ImageDraw

        image = Image.new("RGB", (800, 600), (255, 255, 255))
        draw(ImageDraw.Draw(image))
        path = tmp / name
        image.save(path)
        return path

    def test_blank_white_png_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = gui_smoke.analyse_png(self.make(Path(tmp), "blank.png", lambda d: None))
            self.assertTrue(report["problems"], report)

    def test_rendered_window_like_png_passes(self):
        def draw(d):
            for i in range(0, 600, 24):
                d.rectangle([0, i, 800, i + 12], fill=(30 + i % 200, 60, 120))
            d.rectangle([50, 50, 400, 300], fill=(10, 10, 10))
            d.text((60, 60), "Fidelis", fill=(255, 255, 255))
        with tempfile.TemporaryDirectory() as tmp:
            report = gui_smoke.analyse_png(self.make(Path(tmp), "ui.png", draw))
            self.assertEqual(report["problems"], [], report)

    def test_missing_file_fails(self):
        self.assertTrue(gui_smoke.analyse_png(Path("does-not-exist.png"))["problems"])


class StoredBaselineTests(unittest.TestCase):
    """Baselines on disk are internally consistent and independent of the dumper's own hashing."""

    def baseline_dirs(self):
        return sorted(BASELINES.glob("*/*/gui"))

    def test_raw_planes_hash_to_recorded_digests_and_saved_tiff_matches(self):
        dirs = self.baseline_dirs()
        if not dirs:
            self.skipTest("no baselines generated")
        for gui in dirs:
            for path in sorted(gui.glob("s*.json")):
                dump = json.loads(path.read_text())
                for image in dump["images"]:
                    raws = [gui / "raw" / path.stem / f"{image['index']}_{p}.raw"
                            for p in range(1, image["stackSize"] + 1)]
                    if not all(r.exists() for r in raws):
                        continue
                    combined = hashlib.sha256()
                    for plane_digest, raw in zip(image["planeSha256"], raws):
                        data = raw.read_bytes()
                        self.assertEqual(hashlib.sha256(data).hexdigest(), plane_digest, f"{path.name}")
                        combined.update(data)
                    self.assertEqual(combined.hexdigest(), image["allPlanesSha256"])

    def test_fx001_analytic_values_in_the_stored_baseline(self):
        dirs = list(BASELINES.glob("FX-001/*/gui"))
        if not dirs:
            self.skipTest("no FX-001 baseline")
        expected = json.loads((dirs[0] / "s01_open.json").read_text())["images"][0]["planeSha256"][0]
        dump = dirs[0] / "raw" / "s01_open" / "0_1.raw"
        if dump.exists():  # written by baseline.py; git-ignored, so absent in a fresh clone
            raw = dump.read_bytes()
        else:  # the committed fixture, decoded independently; the hash ties it to the stored (committed) dump
            from PIL import Image

            raw = Image.open(ROOT / "fixtures" / "two-squares.png").tobytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), expected)
        self.assertEqual(len(raw), 64 * 64)
        self.assertEqual(sum(raw) / len(raw), 18.75)  # fixtures/README.md
        self.assertEqual(sorted(set(raw)), [0, 100, 200])
        self.assertEqual(raw.count(100), 256)
        self.assertEqual(raw.count(200), 256)

    def test_saved_tiff_read_back_with_pillow_equals_dumped_mask(self):
        from PIL import Image

        for gui in self.baseline_dirs():
            run = json.loads((gui / "run.json").read_text())
            tiff = ROOT / "verification" / "runs" / run["fixture"] / "gui" / "out" / "saved"
            files = list(tiff.glob("*.tif")) if tiff.exists() else []
            if not files:
                continue
            dump = json.loads((gui / "s10_save_tiff.json").read_text())
            expected = dump["images"][0]["planeSha256"][0]
            pixels = Image.open(files[0]).convert("L").tobytes()
            self.assertEqual(hashlib.sha256(pixels).hexdigest(), expected)


@unittest.skipUnless(fiji_available(), "pinned Fiji or its lock is unavailable")
class DumperSelfTest(unittest.TestCase):
    def test_invert_two_squares_means(self):
        """D18 3.3: dumper checked on an analytic fixture in a live pinned Fiji (headless)."""
        import j12

        fixture = ROOT / "fixtures" / "two-squares.png"
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp)
            out = run_dir / "out"
            out.mkdir()
            dump = j12.q(j12.DUMPER)
            o = str(out).replace("\\", "/")

            def call(tag):
                return (f'r = eval("js", "var DUMP_OUT=\'{o}\'; var DUMP_TAG=\'{tag}\'; var DUMP_RAW=true;\\n" '
                        f'+ File.openAsString({dump})); File.append("{tag}: " + r, "{o}/dumper.log");')

            macro = "\n".join([f"open({j12.q(fixture)});", call("a_open"), 'run("Invert");', call("b_invert"),
                               f'File.saveString("{{}}", "{o}/done.json");',
                               'eval("script", "System.exit(0);");'])
            result = rr.run_macro(macro, run_dir, mode="headless", done_name="out/done.json", timeout=90)
            self.assertTrue(result.ok, result.log)
            before = (out / "raw/a_open/0_1.raw").read_bytes()
            after = (out / "raw/b_invert/0_1.raw").read_bytes()
            self.assertEqual(sum(before) / len(before), 18.75)
            self.assertEqual(sum(after) / len(after), 236.25)
            self.assertEqual(bytes(255 - b for b in before), after)


if __name__ == "__main__":
    unittest.main(verbosity=2)
