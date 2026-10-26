"""Sync the UltraServers mods folder with the trimmed pack over SFTP.

Setup (once):
    pip install paramiko
    copy modpack/server.example.json to modpack/server.json and fill in host/port/username/remote_mods_dir
    set the password in the environment:  $env:ULTRASERVERS_SFTP_PASSWORD = "..."   (never commit it)

Usage (from the repo root):
    python modpack/sync_server.py list                      # show remote mods folder
    python modpack/sync_server.py plan                      # show what would be deleted/uploaded, change nothing
    python modpack/sync_server.py apply --yes               # delete scrapped jars (modpack/out/server-remove.txt)
                                                            # and upload our own jars (modpack/out/server-add.txt)
    python modpack/sync_server.py upload path/to/mod.jar    # upload one or more jars

Nothing is deleted without --yes. Restart the server from the UltraServers panel afterwards.
"""
import argparse, json, os, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def connect(cfg):
    try:
        import paramiko
    except ImportError:
        sys.exit("paramiko is not installed: run  pip install paramiko")
    password = os.environ.get("ULTRASERVERS_SFTP_PASSWORD")
    if not password:
        sys.exit("set ULTRASERVERS_SFTP_PASSWORD in the environment first")
    transport = paramiko.Transport((cfg["host"], int(cfg.get("port", 2022))))
    transport.connect(username=cfg["username"], password=password)
    return paramiko.SFTPClient.from_transport(transport), transport


def load_cfg():
    p = HERE / "server.json"
    if not p.exists():
        sys.exit("modpack/server.json missing; copy server.example.json and fill it in")
    return json.load(open(p, encoding="utf-8"))


def read_list(name):
    p = HERE / "out" / name
    return [l.strip() for l in open(p, encoding="utf-8")] if p.exists() else []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["list", "plan", "apply", "upload"])
    ap.add_argument("files", nargs="*")
    ap.add_argument("--yes", action="store_true", help="actually delete/upload (apply only)")
    a = ap.parse_args()
    cfg = load_cfg()
    mods_dir = cfg.get("remote_mods_dir", "/mods").rstrip("/")

    sftp, transport = connect(cfg)
    try:
        remote = set(sftp.listdir(mods_dir))
        if a.action == "list":
            for n in sorted(remote):
                print(n)
            print(f"{len(remote)} files in {mods_dir}")
            return

        if a.action == "upload":
            for f in a.files:
                src = Path(f)
                print(f"uploading {src.name}")
                sftp.put(str(src), f"{mods_dir}/{src.name}")
            return

        to_remove = [j for j in read_list("server-remove.txt") if j in remote]
        missing = [j for j in read_list("server-remove.txt") if j not in remote]
        to_add = []
        for j in read_list("server-add.txt"):
            local = next(iter(HERE.parent.glob(f"*/build/libs/{j}")), None)
            if local:
                to_add.append(local)
        print(f"remote mods: {len(remote)}")
        print(f"delete ({len(to_remove)}):")
        for j in to_remove:
            print("  -", j)
        if missing:
            print(f"already absent ({len(missing)}): " + ", ".join(missing[:5]) + (" ..." if len(missing) > 5 else ""))
        print(f"upload ({len(to_add)}):")
        for p in to_add:
            print("  +", p.name)
        if a.action == "plan" or not a.yes:
            if a.action == "apply":
                print("\nnothing changed: re-run with --yes to apply")
            return
        for j in to_remove:
            sftp.remove(f"{mods_dir}/{j}")
            print("deleted", j)
        for p in to_add:
            sftp.put(str(p), f"{mods_dir}/{p.name}")
            print("uploaded", p.name)
        print("done; restart the server from the UltraServers panel")
    finally:
        sftp.close(); transport.close()


if __name__ == "__main__":
    main()
