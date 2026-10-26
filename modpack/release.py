"""Local release: build every mod with Gradle, rebuild the pack, and collect everything into dist/<version>/.

    python modpack/release.py [--version 1.0.0] [--skip-build]

Produces dist/<version>/ with: the mod jars (no sources jars, no dev-driver), checklist-grid.json,
checklist-upgrades.json, and the importable pack zip. Nothing is uploaded; share the folder or the zip however you like.
If the GitHub CLI is installed and logged in, add --github to also create a release with these files attached.
"""
import argparse, shutil, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODS = ["level-loss-only", "keyboard-keybinds", "cherished-mobs", "radars", "checklist"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="1.0.0")
    ap.add_argument("--skip-build", action="store_true", help="use the jars already in */build/libs")
    ap.add_argument("--github", action="store_true", help="also create a GitHub release with gh")
    a = ap.parse_args()

    if not a.skip_build:
        subprocess.run([str(ROOT / "gradlew.bat"), "build", "-x", "test"], cwd=ROOT, check=True, shell=True)
    subprocess.run([sys.executable, "-I", "-X", "utf8", str(ROOT / "modpack" / "build_pack.py"), "--version", a.version], cwd=ROOT, check=True)

    dist = ROOT / "dist" / a.version
    if dist.exists():
        shutil.rmtree(dist)
    dist.mkdir(parents=True)
    for m in MODS:
        for jar in (ROOT / m / "build" / "libs").glob("*.jar"):
            if not jar.name.endswith(("-sources.jar", "-javadoc.jar")):
                shutil.copy2(jar, dist / jar.name)
    for name in ("checklist-grid.json", "checklist-upgrades.json"):
        if (ROOT / "modpack" / name).exists():
            shutil.copy2(ROOT / "modpack" / name, dist / name)
    for z in (ROOT / "modpack" / "out").glob("*.zip"):
        shutil.copy2(z, dist / z.name)
    print("release files in", dist)
    for p in sorted(dist.iterdir()):
        print(f"  {p.stat().st_size:>10,}  {p.name}")

    if a.github:
        files = [str(p) for p in sorted(dist.iterdir())]
        subprocess.run(["gh", "release", "create", f"v{a.version}", *files, "--title", f"v{a.version}", "--generate-notes"], cwd=ROOT, check=True, shell=True)
        print("GitHub release created: v" + a.version)


if __name__ == "__main__":
    main()
