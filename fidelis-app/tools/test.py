"""Run the Fidelis JUnit 5 tests inside the pinned Fiji runtime.

Usage:  python fidelis-app/tools/test.py [--select-class FQCN ...] [--tag TAG] [--heap 4g] [--timeout SEC]
                                        [--random-order SEED [--random-methods]]

Tests run in one JVM with the same flags as the application (tools/fijienv.py) and an isolated
user.home, so ImageJ preferences never touch the developer's profile (NFR-MAINT-004).
Tests that need a live Fiji context use sc.fidelis.testkit.FijiFixture.

Every test class starts from the same shared-JVM state: sc.fidelis.testkit.SessionIsolation is registered
through JUnit's extension autodetection (enabled here) and prints one `[isolation]` line per class.
--random-order SEED runs the classes in a seeded random order (JUnit's ClassOrderer$Random) to expose
order dependence; --random-methods also shuffles the methods inside each class with the same seed.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fijienv as env  # noqa: E402


def main() -> None:
    argv = sys.argv[1:]
    heap = argv[argv.index("--heap") + 1] if "--heap" in argv else "4g"
    timeout = float(argv[argv.index("--timeout") + 1]) if "--timeout" in argv else 900.0
    if not env.TEST_CLASSES.exists():
        sys.exit("[test] build first: python fidelis-app/tools/build.py")
    home = env.BUILD / "test-home"
    home.mkdir(parents=True, exist_ok=True)
    cp = [str(env.TEST_CLASSES), str(env.CLASSES), str(env.JUNIT_JAR)] + env.fiji_classpath()
    launcher = ["org.junit.platform.console.ConsoleLauncher", "execute", "--disable-banner",
                "--details=tree", "--fail-if-no-tests"]
    if "--select-class" in argv:
        for i, a in enumerate(argv):
            if a == "--select-class":
                launcher += ["--select-class", argv[i + 1]]
    else:
        launcher += ["--scan-classpath", str(env.TEST_CLASSES)]
    if "--tag" in argv:
        launcher += ["--include-tag", argv[argv.index("--tag") + 1]]
    launcher += ["--config", "junit.jupiter.extensions.autodetection.enabled=true"]
    if "--random-order" in argv:
        seed = int(argv[argv.index("--random-order") + 1])
        launcher += ["--config", "junit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$Random",
                     "--config", f"junit.jupiter.execution.order.random.seed={seed}"]
        methods = "--random-methods" in argv
        if methods:
            launcher += ["--config",
                         "junit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random"]
        print(f"[test] random class order, seed {seed}" + (" (methods shuffled too)" if methods else ""))
    props = {"fidelis.repo.dir": str(env.REPO_DIR), "java.awt.headless": "false"}
    args = env.jvm_flags(heap=heap, user_home=home, extra_props=props) + ["-cp", ";".join(cp)] + launcher
    argfile = env.write_argfile(env.BUILD / "test.args", args)
    proc = subprocess.Popen([env.jdk_bin("java"), f"@{argfile}"], cwd=str(env.fiji_dir()))
    try:
        rc = proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        # keep the evidence of a stall before ending it: one thread dump of the test JVM
        dump = env.BUILD / "test-timeout-threads.txt"
        try:
            with open(dump, "w", encoding="utf-8") as fh:
                subprocess.run([env.jdk_bin("jcmd"), str(proc.pid), "Thread.print"], stdout=fh,
                               stderr=subprocess.STDOUT, timeout=60)
        except (OSError, subprocess.TimeoutExpired) as e:
            print(f"[test] thread dump failed: {e}")
        print(f"[test] timeout after {timeout}s; thread dump in {dump}; terminating JVM {proc.pid}")
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
        else:
            proc.kill()
        rc = 124
    sys.exit(rc)


if __name__ == "__main__":
    main()
