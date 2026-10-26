"""Set up and boot a local, headless NeoForge test server with the trimmed pack, then grep its log.

    python modpack/boot_server.py setup   <dir>        # install NeoForge server into <dir> (needs the installer jar)
    python modpack/boot_server.py sync    <dir>        # copy the pack's mods + configs (+ checklist files) into <dir>
    python modpack/boot_server.py run     <dir> [secs] # start it headless, wait for "Done" or a crash, stop it, summarize the log
    python modpack/boot_server.py report  <dir>        # just summarize <dir>/logs/latest.log
    python modpack/boot_server.py baseline <dir>       # instead of sync: the ORIGINAL pack's full server-side set, for comparison
    python modpack/boot_server.py errors  <dir> > file # normalized unique error lines of the last boot
    python modpack/boot_server.py diff    <dir> <file> # errors of the last boot vs a saved error list

Prerequisites (both need your OK, so the script never downloads or accepts anything itself):
  - the installer: neoforge-21.1.248-installer.jar placed in modpack/tools/
  - eula.txt in <dir> containing eula=true (Mojang's EULA is yours to accept)
Java: uses the Gradle-provisioned JDK 21 under ~/.gradle/jdks if JAVA21 is not set.
"""
import os, re, shutil, subprocess, sys, time, glob, json
from pathlib import Path

HERE = Path(__file__).resolve().parent
NEO_VERSION = "21.1.248"
INSTALLER = HERE / "tools" / f"neoforge-{NEO_VERSION}-installer.jar"
INSTANCE = Path.home() / "curseforge/minecraft/Instances/Create Aeronautics - Skybound  Create Aeronautics & Create Addons with Quests"
def client_only_prefixes():
    p = HERE / "client-only-mods.txt"
    return tuple(l.strip().lower() for l in open(p, encoding="utf-8") if l.strip() and not l.startswith("#")) if p.exists() else ()


def is_client_only(name):
    return name.lower().startswith(client_only_prefixes())


def java21():
    j = os.environ.get("JAVA21")
    if j:
        return j
    for c in sorted(glob.glob(str(Path.home() / ".gradle/jdks/*/bin/java.exe")), reverse=True):
        return c
    return "java"


def setup(d):
    d.mkdir(parents=True, exist_ok=True)
    if not INSTALLER.exists():
        sys.exit(f"installer missing: put neoforge-{NEO_VERSION}-installer.jar in {INSTALLER.parent}")
    subprocess.run([java21(), "-jar", str(INSTALLER), "--install-server", str(d)], check=True)
    print("installed; now create", d / "eula.txt", "with eula=true, then run: sync, run")


def sync_baseline(d):
    """The ORIGINAL pack's full mod set (minus client-only jars) + its own config/kubejs: the comparison baseline."""
    mods = d / "mods"
    if mods.exists():
        shutil.rmtree(mods)
    mods.mkdir()
    n = 0
    for jar in (INSTANCE / "mods").glob("*.jar"):
        if is_client_only(jar.name):
            continue
        shutil.copy2(jar, mods / jar.name); n += 1
    for sub in ("config", "kubejs", "defaultconfigs"):
        if (d / sub).exists():
            shutil.rmtree(d / sub)
        if (INSTANCE / sub).exists():
            shutil.copytree(INSTANCE / sub, d / sub, ignore=shutil.ignore_patterns("*.OLD", "*.bak"))
    print(f"baseline: {n} jars from the original instance")


def errors_of(d):
    txt = (d / "boot-console.txt").read_text(encoding="utf-8", errors="replace") if (d / "boot-console.txt").exists() else ""
    def norm(l):
        l = re.sub(r"^\[[^\]]*\]\s*", "", l)            # timestamp
        l = re.sub(r"^\[[^/\]]+/(ERROR|FATAL)\]", r"[\1]", l)  # thread name
        l = re.sub(r"@[0-9a-f]{6,}", "@", l)              # object hashes
        return l.strip()
    return sorted({norm(l) for l in txt.splitlines() if "/ERROR]" in l or "/FATAL]" in l})


def diff(d, baseline_file):
    base = set(Path(baseline_file).read_text(encoding="utf-8").splitlines())
    cur = set(errors_of(d))
    new, gone = sorted(cur - base), sorted(base - cur)
    print(f"errors now: {len(cur)} | baseline: {len(base)} | NEW: {len(new)} | gone: {len(gone)}")
    for l in new: print("NEW:", l[:260])
    for l in gone[:15]: print("gone:", l[:160])


def sync(d):
    pack = HERE / "out" / "pack"
    manifest = json.load(open(pack / "manifest.json", encoding="utf-8"))
    instance = json.load(open(INSTANCE / "minecraftinstance.json", encoding="utf-8"))
    by_project = {a["addonID"]: a for a in instance["installedAddons"]}
    mods = d / "mods"
    if mods.exists():
        shutil.rmtree(mods)
    mods.mkdir()
    n = 0; skipped = []
    for f in manifest["files"]:
        a = by_project.get(f["projectID"])
        if a and a["categorySection"]["path"] == "mods":
            src = INSTANCE / "mods" / a["fileNameOnDisk"]
            if is_client_only(src.name):
                skipped.append(src.name)
            elif src.exists():
                shutil.copy2(src, mods / src.name); n += 1
            else:
                print("WARNING: not in local instance, download it:", a["fileNameOnDisk"])
        elif not a:
            print("WARNING: new mod not available locally (download from CurseForge):", f)
    for jar in (pack / "overrides" / "mods").glob("*.jar"):
        if is_client_only(jar.name):
            skipped.append(jar.name); continue
        shutil.copy2(jar, mods / jar.name); n += 1
    print(f"skipped {len(skipped)} client-only jars: {', '.join(skipped)}")
    for sub in ("config", "kubejs", "defaultconfigs"):
        src = pack / "overrides" / sub
        if src.exists():
            if (d / sub).exists():
                shutil.rmtree(d / sub)
            shutil.copytree(src, d / sub)
    print(f"synced {n} jars + config/kubejs/defaultconfigs into {d}")


def run(d, secs):
    if not (d / "eula.txt").exists() or "eula=true" not in (d / "eula.txt").read_text():
        sys.exit("eula.txt with eula=true is missing; accept Mojang's EULA yourself first")
    args_file = next(iter(glob.glob(str(d / "libraries/net/neoforged/neoforge/*/win_args.txt"))), None)
    if not args_file:
        sys.exit("NeoForge server not installed here; run setup first")
    props = d / "server.properties"
    if not props.exists():
        props.write_text("online-mode=false\nlevel-name=testworld\nmax-players=2\nview-distance=4\nspawn-protection=0\n")
    cmd = [java21(), "-Xmx8G", "-Xms2G", f"@{Path(args_file).relative_to(d)}", "--nogui"]
    print("starting:", " ".join(cmd))
    log = open(d / "boot-console.txt", "w", encoding="utf-8")
    p = subprocess.Popen(cmd, cwd=d, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
    t0 = time.time(); status = "timeout"
    while time.time() - t0 < secs:
        time.sleep(5)
        if p.poll() is not None:
            status = f"exited {p.returncode}"; break
        txt = (d / "boot-console.txt").read_text(encoding="utf-8", errors="replace")
        if re.search(r'Done \([\d.]+s\)! For help', txt):
            status = "started"; break
        if "Failed to start the minecraft server" in txt or "A fatal error has been detected" in txt or "crash-reports" in txt:
            status = "crashed"; break
    if p.poll() is None:
        try:
            p.stdin.write("stop\n"); p.stdin.flush()
            p.wait(60)
        except Exception:
            p.kill()
    print("status:", status, f"after {time.time()-t0:.0f}s")
    report(d)


def report(d):
    txt = (d / "boot-console.txt").read_text(encoding="utf-8", errors="replace") if (d / "boot-console.txt").exists() else ""
    lines = txt.splitlines()
    errs = [l for l in lines if "/ERROR]" in l or "/FATAL]" in l]
    warns = [l for l in lines if "/WARN]" in l]
    missing = [l for l in lines if re.search(r"Missing or unsupported mandatory dependencies|requires .* but", l)]
    unknown = [l for l in lines if re.search(r"Unknown (block|entity|item)|Failed to load|not found", l, re.I)]
    print(f"log lines: {len(lines)} | errors: {len(errs)} | warnings: {len(warns)}")
    for l in missing[:20]: print("DEP:", l[-200:])
    for l in errs[:40]: print("ERR:", l[-220:])
    print("--- most common warning sources:")
    import collections
    c = collections.Counter(re.sub(r".*\[([^\]]+)/WARN\].*", r"\1", l)[:60] for l in warns)
    for k, v in c.most_common(15): print(f"{v:5}  {k}")
    crash = sorted((d / "crash-reports").glob("*.txt")) if (d / "crash-reports").exists() else []
    if crash:
        print("--- latest crash report:", crash[-1]); print("\n".join(crash[-1].read_text(errors='replace').splitlines()[:60]))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    cmd, d = sys.argv[1], Path(sys.argv[2]).resolve()
    {"setup": lambda: setup(d), "sync": lambda: sync(d), "baseline": lambda: sync_baseline(d),
     "run": lambda: run(d, int(sys.argv[3]) if len(sys.argv) > 3 else 900), "report": lambda: report(d),
     "errors": lambda: print("\n".join(errors_of(d))),
     "diff": lambda: diff(d, sys.argv[3])}[cmd]()
