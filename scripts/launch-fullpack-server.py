"""Start / stop the production NeoForge 21.1.248 dedicated server in dev-driver/run-fullpack-server (the whole pack,
server side) with this repo's mod jars and the dev-driver jar copied into its mods folder.

    python scripts/launch-fullpack-server.py start scripts/driver/server-migrate.txt [--xmx 8G]
    python scripts/launch-fullpack-server.py signal      # create the stop flag a `waitfile` line in the server script waits for
    python scripts/launch-fullpack-server.py wait [secs] # block until the server process has exited
    python scripts/launch-fullpack-server.py status

The server dir is set up once by modpack/boot_server.py setup/sync (NeoForge installer, mods, configs); this script only
launches it, from that dir, with -Ddevdriver.script so ServerDriver runs the given script (ending in `quit`).
It never touches the other session's test server in C:\\Users\\lucas\\vibecraft\\testserver."""
import glob, os, shutil, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER = ROOT / "dev-driver" / "run-fullpack-server"
PID = SERVER / "server.pid"
FLAG = SERVER / "stop.flag"
MODS = ["level-loss-only", "keyboard-keybinds", "cherished-mobs", "radars", "checklist", "dev-driver"]


def java21():
    j = os.environ.get("JAVA21")
    if j:
        return j
    for c in sorted(glob.glob(str(Path.home() / ".gradle/jdks/*/bin/java.exe")), reverse=True):
        if "21" in c:
            return c
    raise SystemExit("no JDK 21 under ~/.gradle/jdks; set JAVA21")


def copy_our_jars():
    mods = SERVER / "mods"
    for name in MODS:
        jars = sorted((ROOT / name / "build" / "libs").glob("*.jar"))
        jars = [j for j in jars if not j.name.endswith(("-sources.jar", "-javadoc.jar"))]
        if not jars:
            print(f"warning: no jar built for {name}")
            continue
        src = jars[-1]
        stem = src.name.split("-1.21.1")[0]
        for old in mods.glob(f"{stem}-*.jar"):
            old.unlink()
        shutil.copy2(src, mods / src.name)
        print(f"  {src.name} -> mods/")


def running():
    if not PID.exists():
        return None
    pid = int(PID.read_text().strip() or 0)
    if not pid:
        return None
    out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}"], capture_output=True, text=True).stdout
    return pid if str(pid) in out else None


def start(script, xmx):
    if running():
        raise SystemExit(f"server already running (pid {running()})")
    FLAG.unlink(missing_ok=True)
    copy_our_jars()
    script_path = (ROOT / script).resolve()
    log = SERVER / "console.log"
    args = [java21(), f"-Xmx{xmx}", "-Xms2G", f"-Ddevdriver.script={script_path}",
            "@libraries/net/neoforged/neoforge/21.1.248/win_args.txt", "--nogui"]
    print("launching:", " ".join(args))
    with open(log, "w", encoding="utf-8") as out:
        proc = subprocess.Popen(args, cwd=SERVER, stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS)
    PID.write_text(str(proc.pid))
    print(f"server pid {proc.pid}; console in {log}")


def wait(secs):
    deadline = time.time() + secs
    while running() and time.time() < deadline:
        time.sleep(5)
    print("exited" if not running() else f"still running after {secs}s (pid {running()})")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "start":
        xmx = "8G"
        if "--xmx" in sys.argv:
            xmx = sys.argv[sys.argv.index("--xmx") + 1]
        start(sys.argv[2], xmx)
    elif cmd == "signal":
        FLAG.write_text("stop")
        print(f"wrote {FLAG}")
    elif cmd == "wait":
        wait(int(sys.argv[2]) if len(sys.argv) > 2 else 600)
    else:
        print(f"running pid: {running()}")
