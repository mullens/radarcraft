"""Local release: build every mod with Gradle, rebuild the pack, and collect everything into dist/<version>/.

    python modpack/release.py [--version 1.0.0] [--skip-build] [--publish]

Produces dist/<version>/ with: the mod jars (no sources jars, no dev-driver), checklist-grid.json,
checklist-upgrades.json, mods.lock.json, the updater script and the importable pack zip.
--publish copies all of it to releases/latest/ in the repo, commits and pushes: friends and the updater fetch from
https://raw.githubusercontent.com/mullens/radarcraft/main/releases/latest/<file>. No CI, no GitHub CLI involved.
"""
import argparse, shutil, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODS = ["level-loss-only", "keyboard-keybinds", "cherished-mobs", "radars", "checklist", "food-buffs"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="1.0.0")
    ap.add_argument("--skip-build", action="store_true", help="use the jars already in */build/libs")
    ap.add_argument("--github", action="store_true", help="also create a GitHub release with gh (not installed here)")
    ap.add_argument("--publish", action="store_true", help="copy the release into releases/latest in the repo, commit and push")
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
    for name in ("checklist-grid.json", "checklist-upgrades.json", "foodbuffs.json"):
        if (ROOT / "modpack" / name).exists():
            shutil.copy2(ROOT / "modpack" / name, dist / name)
    for z in (ROOT / "modpack" / "out").glob("*.zip"):
        shutil.copy2(z, dist / z.name)
    if (ROOT / "modpack" / "out" / "mods.lock.json").exists():
        shutil.copy2(ROOT / "modpack" / "out" / "mods.lock.json", dist / "mods.lock.json")
    updater = ROOT / "scripts" / "friends" / "Update-Radarcraft.ps1"
    if updater.exists():
        shutil.copy2(updater, dist / updater.name)   # friends grab the updater from the release page too
    print("release files in", dist)
    for p in sorted(dist.iterdir()):
        print(f"  {p.stat().st_size:>10,}  {p.name}")

    if a.publish:
        # No CI, no GitHub CLI: the release lives in the repo itself at releases/latest (public raw URLs).
        pub = ROOT / "releases" / "latest"
        if pub.exists():
            shutil.rmtree(pub)
        shutil.copytree(dist, pub)
        (pub / "VERSION").write_text(a.version + "\n")
        subprocess.run(["git", "add", "-A", "releases/latest"], cwd=ROOT, check=True)
        subprocess.run(["git", "commit", "-q", "-m", f"Release {a.version}\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"], cwd=ROOT, check=False)
        subprocess.run(["git", "push"], cwd=ROOT, check=True)
        print(f"published to releases/latest and pushed: https://github.com/mullens/radarcraft/tree/main/releases/latest")

    if a.github:
        files = [str(p) for p in sorted(dist.iterdir())]
        subprocess.run(["gh", "release", "create", f"v{a.version}", *files, "--title", f"v{a.version}", "--generate-notes"], cwd=ROOT, check=True, shell=True)
        print("GitHub release created: v" + a.version)


if __name__ == "__main__":
    main()
