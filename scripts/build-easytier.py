#!/usr/bin/env python3
"""Build the optional EasyTier JNI library from an immutable public revision.

No runtime network profile or signing credential is a build input. Requires
Rust >= 1.95, the requested Android Rust targets, NDK >= r26, and protoc.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess


REVISION = "4a10d1c2b93beae60ff0d6d3329463c1ad6928b2"
REPOSITORY = "https://github.com/EasyTier/EasyTier.git"
TARGETS = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android"),
    "x86_64": ("x86_64-linux-android", "x86_64-linux-android"),
    "armeabi-v7a": ("armv7-linux-androideabi", "armv7a-linux-androideabi"),
    "x86": ("i686-linux-android", "i686-linux-android"),
}


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def read(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=TARGETS, action="append")
    parser.add_argument("--ndk", default=os.environ.get("ANDROID_NDK_HOME")
                        or os.environ.get("ANDROID_NDK_ROOT") or os.environ.get("NDK_HOME"))
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if not args.ndk:
        parser.error("set ANDROID_NDK_HOME or pass --ndk")
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    root = Path(__file__).resolve().parents[1]
    ndk = Path(args.ndk).expanduser().resolve()
    host = {"Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}.get(platform.system())
    if not host:
        parser.error("this build script supports Linux and macOS")
    llvm = ndk / "toolchains/llvm/prebuilt" / host
    if not (llvm / "bin/llvm-readelf").is_file():
        parser.error("NDK LLVM tools not found")
    if not shutil.which("protoc") and not os.environ.get("PROTOC"):
        parser.error("protoc must be on PATH or configured with PROTOC")
    build = root / "build/easytier"
    source = build / "source"
    output = build / "jniLibs"
    build.mkdir(parents=True, exist_ok=True)
    git = ["git"]
    # Optional standard proxy supplied by the builder; no personal proxy is
    # prescribed or embedded in the public build configuration.
    if os.environ.get("EASYTIER_GIT_PROXY"):
        git += ["-c", "http.proxy=" + os.environ["EASYTIER_GIT_PROXY"]]
    if not (source / ".git").exists():
        source.mkdir(parents=True, exist_ok=True)
        run(git + ["init", str(source)])
        run(git + ["-C", str(source), "remote", "add", "origin", REPOSITORY])
    if read(git + ["-C", str(source), "remote", "get-url", "origin"]) != REPOSITORY:
        raise RuntimeError("refusing an unexpected EasyTier source remote")
    exists = subprocess.run(git + ["-C", str(source), "cat-file", "-e", REVISION + "^{commit}"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not exists:
        run(git + ["-C", str(source), "fetch", "--depth=1", "origin", REVISION])
    logger_path = "easytier-contrib/easytier-android-jni/src/logger.rs"
    pristine_logger = read(git + ["-C", str(source), "show", REVISION + ":" + logger_path]) + "\n"
    if pristine_logger.count("log::LevelFilter::Debug") != 1:
        raise RuntimeError("the pinned JNI logger no longer matches the reviewed patch")
    quiet_logger = pristine_logger.replace("log::LevelFilter::Debug", "log::LevelFilter::Off")
    # A repeat build may contain precisely our documented logging patch. Never
    # reset arbitrary changes in a developer's cached source tree.
    dirty = read(git + ["-C", str(source), "status", "--porcelain", "--untracked-files=no"])
    if dirty and (dirty != "M " + logger_path and dirty != " M " + logger_path):
        raise RuntimeError("cached EasyTier source has unexpected modifications")
    if dirty and (source / logger_path).read_text() != quiet_logger:
        raise RuntimeError("cached JNI logger differs from the reviewed patch")
    if not dirty:
        run(git + ["-C", str(source), "checkout", "--detach", REVISION])
    if read(git + ["-C", str(source), "rev-parse", "HEAD"]) != REVISION:
        raise RuntimeError("cached EasyTier revision mismatch")
    if (source / logger_path).read_text() != quiet_logger:
        (source / logger_path).write_text(quiet_logger)
    environment = os.environ.copy()
    environment.pop("KCP_SYS_EXTRA_HEADER_PATH", None)
    environment["CARGO_TARGET_DIR"] = str(build / "target")
    environment["CARGO_BUILD_JOBS"] = str(args.jobs)
    environment["PATH"] = str(llvm / "bin") + os.pathsep + environment["PATH"]
    environment["CARGO_NET_GIT_FETCH_WITH_CLI"] = "true"
    if os.environ.get("EASYTIER_GIT_PROXY"):
        # Cargo's git dependencies use the same explicit proxy as the checkout.
        environment["GIT_CONFIG_COUNT"] = "1"
        environment["GIT_CONFIG_KEY_0"] = "http.proxy"
        environment["GIT_CONFIG_VALUE_0"] = os.environ["EASYTIER_GIT_PROXY"]
    records = []
    for abi in dict.fromkeys(args.abi or ["arm64-v8a"]):
        rust_target, clang_target = TARGETS[abi]
        target_env = environment.copy()
        env_suffix = rust_target.replace("-", "_")
        compiler = llvm / "bin" / (clang_target + "31-clang")
        target_env["CC_" + env_suffix] = str(compiler)
        target_env["CXX_" + env_suffix] = str(compiler) + "++"
        target_env["AR_" + env_suffix] = str(llvm / "bin/llvm-ar")
        target_env["CARGO_TARGET_" + env_suffix.upper() + "_LINKER"] = str(compiler)
        include_target = "arm-linux-androideabi" if abi == "armeabi-v7a" else rust_target
        target_env["BINDGEN_EXTRA_CLANG_ARGS"] = (
            f"--sysroot={llvm / 'sysroot'} -I{llvm / 'sysroot/usr/include' / include_target}"
        )
        run(["cargo", "rustc", "--locked", "--release", "-p", "easytier-android-jni",
             "--target", rust_target, "--", "-C", "link-arg=-Wl,-z,max-page-size=16384"],
            cwd=source, env=target_env)
        library = build / "target" / rust_target / "release/libeasytier_android_jni.so"
        headers = read([str(llvm / "bin/llvm-readelf"), "--program-headers", "--wide", str(library)])
        loads = [line.split() for line in headers.splitlines() if line.lstrip().startswith("LOAD ")]
        if not loads or any(int(line[-1], 16) < 16384 for line in loads):
            raise RuntimeError("native library failed the 16 KiB ELF alignment gate")
        dynamic = read([str(llvm / "bin/llvm-readelf"), "--dynamic", str(library)])
        if "libeasytier_ffi.so" in dynamic:
            raise RuntimeError("JNI unexpectedly depends on an unpackaged FFI shared library")
        target = output / abi / library.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(library, target)
        records.append({"abi": abi, "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
                        "elf_load_alignment_at_least_16k": True})
    (build / "build-info.json").write_text(json.dumps({
        "repository": REPOSITORY, "revision": REVISION,
        "local_patch": "JNI logger LevelFilter::Debug -> LevelFilter::Off",
        "libraries": records,
    }, indent=2) + "\n")
    # The optional APK must ship dependency notices as well as the JNI binary.
    # Missing source notices stop the build until reviewed supplements exist.
    report_args = [os.environ.get("PYTHON", "python3"),
                   str(root / "scripts/easytier-license-report.py")]
    for record in records:
        report_args += ["--target", TARGETS[record["abi"]][0]]
    run(report_args, env=environment)
    print("Verified EasyTier JNI libraries:", ", ".join(record["abi"] for record in records))


if __name__ == "__main__":
    main()
