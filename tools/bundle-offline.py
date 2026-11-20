"""离线环境打包：下载 Ubuntu rootfs + Node.js（SHA-256 全程校验）→ assets/offline/。
由 gradle bundleOfflineEnv 任务调用（OFFLINE_OUT / OFFLINE_CACHE 环境变量），
也可独立运行：python tools/bundle-offline.py [--abis arm64,amd64]
"""
import hashlib
import json
import os
import sys
import urllib.request

OUT = os.environ.get("OFFLINE_OUT", os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "offline"))
CACHE = os.environ.get("OFFLINE_CACHE", os.path.join(os.path.dirname(__file__), "..", ".offline-cache"))
ROOTFS_VERSION = os.environ.get("OFFLINE_ROOTFS_VERSION", "24.04.3")
NODE_VERSION = os.environ.get("OFFLINE_NODE_VERSION", "v22.20.0")
ABIS = [a.strip() for a in (os.environ.get("OFFLINE_ABIS") or "arm64,amd64").split(",") if a.strip()]

OUT = os.path.abspath(OUT)
CACHE = os.path.abspath(CACHE)
NODE_ARCH = {"arm64": "arm64", "amd64": "x64"}


def download(urls, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    last = None
    for url in urls:
        try:
            print("  下载:", url[:90], flush=True)
            req = urllib.request.Request(url, headers={"User-Agent": "zcodea-bundle"})
            conn = urllib.request.urlopen(req, timeout=30)
            with conn as r, open(tmp, "wb") as f:
                while True:
                    chunk = r.read(1 << 16)
                    if not chunk:
                        break
                    f.write(chunk)
            if os.path.getsize(tmp) == 0:
                raise IOError("empty body")
            if os.path.exists(dest):
                os.remove(dest)
            os.replace(tmp, dest)
            return dest
        except Exception as e:
            print("    失败:", e, flush=True)
            last = e
    raise last or RuntimeError("no mirrors")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch_text(urls):
    for url in urls:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "zcodea-bundle"})
            return urllib.request.urlopen(req, timeout=30).read().decode()
        except Exception as e:
            print("    清单获取失败:", e, flush=True)
    return None


def expected_sha(text, filename):
    if not text:
        return None
    for line in text.splitlines():
        line = line.strip()
        if line.endswith(filename) or line.endswith("*" + filename):
            return line.split()[0].lower()
    return None


def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(CACHE, exist_ok=True)
    manifest = {}

    for ubuntu_arch in ABIS:
        node_arch = NODE_ARCH[ubuntu_arch]

        # ---- rootfs ----
        rootfs_remote = f"ubuntu-base-{ROOTFS_VERSION}-base-{ubuntu_arch}.tar.gz"
        rootfs_key = f"rootfs-{ubuntu_arch}"
        rootfs_urls = [
            f"https://cdimage.ubuntu.com/ubuntu-base/releases/{ROOTFS_VERSION}/release/{rootfs_remote}",
            f"https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/{ROOTFS_VERSION}/release/{rootfs_remote}",
        ]
        sums_urls = [
            f"https://cdimage.ubuntu.com/ubuntu-base/releases/{ROOTFS_VERSION}/release/SHA256SUMS",
            f"https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/{ROOTFS_VERSION}/release/SHA256SUMS",
        ]
        f = download(rootfs_urls, os.path.join(CACHE, rootfs_remote))
        exp = expected_sha(fetch_text(sums_urls), rootfs_remote)
        if exp:
            actual = sha256(f)
            if actual != exp:
                raise SystemExit(f"rootfs sha256 mismatch: {actual} != {exp}")
            print("  sha256 校验通过")
        dest = os.path.join(OUT, rootfs_key + ".tgz")
        with open(f, "rb") as a, open(dest, "wb") as b:
            b.write(a.read())
        manifest[rootfs_key] = sha256(dest)
        print(f"[ok] {rootfs_key} ({os.path.getsize(dest) >> 20}MB)", flush=True)

        # ---- node ----
        node_file = f"node-{NODE_VERSION}-linux-{node_arch}.tar.gz"
        node_key = f"node-{node_arch}"
        node_urls = [
            f"https://nodejs.org/dist/{NODE_VERSION}/{node_file}",
            f"https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/{NODE_VERSION}/{node_file}",
            f"https://cdn.npmmirror.com/binaries/node/{NODE_VERSION}/{node_file}",
        ]
        shasums_urls = [
            f"https://nodejs.org/dist/{NODE_VERSION}/SHASUMS256.txt",
            f"https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/{NODE_VERSION}/SHASUMS256.txt",
        ]
        f = download(node_urls, os.path.join(CACHE, node_file))
        exp = expected_sha(fetch_text(shasums_urls), node_file)
        if exp:
            actual = sha256(f)
            if actual != exp:
                raise SystemExit(f"node sha256 mismatch: {actual} != {exp}")
            print("  sha256 校验通过")
        dest = os.path.join(OUT, node_key + ".tgz")
        with open(f, "rb") as a, open(dest, "wb") as b:
            b.write(a.read())
        manifest[node_key] = sha256(dest)
        print(f"[ok] {node_key} ({os.path.getsize(dest) >> 20}MB)", flush=True)

    manifest["_rootfsVersion"] = ROOTFS_VERSION
    manifest["_nodeVersion"] = NODE_VERSION
    with open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=1)
    total = sum(os.path.getsize(os.path.join(OUT, x)) for x in os.listdir(OUT))
    print(f"[done] {len(manifest)} assets, {total >> 20}MB total -> {OUT}", flush=True)


if __name__ == "__main__":
    if "--abis" in sys.argv:
        ABIS[:] = [a.strip() for a in sys.argv[sys.argv.index("--abis") + 1].split(",")]
    main()
