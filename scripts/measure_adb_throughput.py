#!/usr/bin/env python3
"""Measure Mac -> Android throughput over the same path SharpScreen uses on USB.

SharpScreen streams over `adb reverse`: the Android client connects to
127.0.0.1:<port> on the device, adbd carries it over USB, and the adb server
on the Mac hands it to the Mac host. Lossless (LZ4) region updates are only
practical if this path is fast enough, so measure it before building them.

The device side is the stock toybox `nc` piped into `dd`, so nothing needs to
be installed on the tablet; `dd` reports what actually arrived.

Usage:
    scripts/measure_adb_throughput.py [--mb 512] [--runs 3] [--port 54399] [-s SERIAL]
"""

import argparse
import re
import shutil
import socket
import subprocess
import sys
import threading
import time

CHUNK = 1 << 20  # 1 MiB writes, comparable to a large encoded frame


def adb_path() -> str:
    path = shutil.which("adb") or "/opt/homebrew/bin/adb"
    if not shutil.which(path) and not path.startswith("/"):
        sys.exit("adb not found")
    return path


def adb(args, serial):
    cmd = [adb_path()] + (["-s", serial] if serial else []) + args
    return cmd


def parse_dd(text: str):
    """toybox dd: '<n> bytes (<h>) copied, <s> s, <rate>/s'."""
    m = re.search(r"(\d+) bytes .*copied, ([\d.]+) s", text)
    if not m:
        return None
    return int(m.group(1)), float(m.group(2))


def one_run(port: int, total_bytes: int, serial):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(1)
    server.settimeout(15)

    # Device side: connect through adb reverse and count what arrives.
    # nc must keep its stdin open: on EOF it drops the connection before
    # reading anything, and adb would still swallow the whole send into its
    # own buffers. `sleep` holds stdin; dd reports once nc sees the close.
    device_cmd = f"sleep 300 | nc 127.0.0.1 {port} | dd of=/dev/null bs=1048576 2>&1"
    proc = subprocess.Popen(adb(["shell", device_cmd], serial),
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    try:
        conn, _ = server.accept()
    except socket.timeout:
        proc.kill()
        server.close()
        raise SystemExit("device never connected (is adb reverse set up?)")

    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    payload = bytes(CHUNK)
    sent = 0
    t0 = time.perf_counter()
    while sent < total_bytes:
        n = min(CHUNK, total_bytes - sent)
        conn.sendall(payload[:n])
        sent += n
    t_sent = time.perf_counter() - t0
    conn.close()
    server.close()

    # dd prints its summary when nc exits; the shell itself waits for sleep.
    lines = []
    deadline = time.time() + 120
    for line in proc.stdout:
        lines.append(line)
        if "copied" in line or time.time() > deadline:
            break
    t_done = time.perf_counter() - t0
    proc.kill()
    proc.wait()
    out = "".join(lines)
    dd = parse_dd(out)
    return sent, t_sent, t_done, dd, out.strip()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mb", type=int, default=512, help="MiB to send per run (default 512)")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--port", type=int, default=54399, help="port to reverse (not SharpScreen's 54321)")
    ap.add_argument("-s", "--serial", help="adb device serial")
    a = ap.parse_args()

    subprocess.run(adb(["reverse", f"tcp:{a.port}", f"tcp:{a.port}"], a.serial), check=True,
                   stdout=subprocess.DEVNULL)
    total = a.mb * CHUNK
    rates = []
    try:
        for i in range(1, a.runs + 1):
            sent, t_sent, t_done, dd, raw = one_run(a.port, total, a.serial)
            if dd is None:
                print(f"run {i}: could not parse device dd output: {raw!r}")
                continue
            got, dev_s = dd
            mbps = got * 8 / dev_s / 1e6
            rates.append(mbps)
            print(f"run {i}: device received {got / CHUNK:.0f} MiB in {dev_s:.2f} s "
                  f"= {got / dev_s / 1e6:.0f} MB/s ({mbps:.0f} Mbit/s); "
                  f"Mac send loop {t_sent:.2f} s, end-to-end {t_done:.2f} s"
                  + ("" if got == sent else f"  [!] sent {sent} bytes"))
    finally:
        subprocess.run(adb(["reverse", "--remove", f"tcp:{a.port}"], a.serial),
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    if rates:
        best, mean = max(rates), sum(rates) / len(rates)
        print(f"\nadb reverse throughput: mean {mean:.0f} Mbit/s ({mean / 8:.0f} MB/s), best {best:.0f} Mbit/s")
        # What that means for a 2960x1848 BGRA panel (the Tab S11 Ultra).
        frame = 2960 * 1848 * 4
        print(f"Full 2960x1848 BGRA frames/s at the mean rate: raw {mean / 8 * 1e6 / frame:.1f}, "
              f"LZ4 at 4:1 {mean / 8 * 1e6 * 4 / frame:.1f}, at 10:1 {mean / 8 * 1e6 * 10 / frame:.1f}")


if __name__ == "__main__":
    main()
