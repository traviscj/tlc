#!/usr/bin/env python3
"""Record each demo as one video: the demo's terminal output on the left, the TLC console on the right.

  python scripts/record_demos.py            # all nine -> docs/recordings/NN-name.mp4
  python scripts/record_demos.py 05 09      # just these
  TLC_URL=http://localhost:8792 python scripts/record_demos.py --out /tmp/rec

Needs a running, seeded TLC (scripts/seed.sh), Playwright with a local Chrome, and an ffmpeg with libx264
(on PATH, or `pip install imageio-ffmpeg`, which bundles one).

How: a headless Chrome page (1920x1080) holds a <pre> the demo's output is appended to as it arrives and an
<iframe> of the console (/?user=…#target=…). The demo runs for real against the server, slowed down with
TLC_DEMO_PACE / TLC_DEMO_CMD_PACE (see scripts/demos/_lib.sh). The page is screenshotted ~8x a second and the
PNGs, with their real timestamps, are encoded to H.264. Two things the recorder does to the console that a
person watching it would not: it calls the console's own tick() + panel.refresh() whenever the demo prints
(unattended it polls every few seconds), and it moves #target= to the record the current step is about
(FOLLOW below). Nothing in the right pane is drawn by this script.
"""
import argparse, hashlib, html, os, queue, re, shutil, subprocess, sys, tempfile, threading, time
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
W, H, TERM_W = 1920, 1080, 800

# Per demo: who the console is viewed as, the record it opens on, and (regex on an output line -> record) moves.
# {RUN} is the run suffix; \1 is the regex's group. "thread" opens that thread in the panel instead of moving.
PC = "sentinel/proposed_changes/"
DEMOS = {
    "01-entities": dict(target=PC + "e{RUN}"),
    "02-tags": dict(target=PC + "t{RUN}", follow=[(r"tag add (T-\S+) pinned", dict(target=r"\1"))]),
    "03-logs": dict(target=PC + "l{RUN}"),
    "04-comments": dict(target=PC + "c{RUN}", follow=[(r"thread show (T-\S+)", dict(thread=r"\1"))]),
    "05-approvals": dict(target=PC + "a{RUN}", follow=[
        (r"^## 2\. ", dict(target=PC + "b{RUN}")), (r"^## 3\. ", dict(target=PC + "c{RUN}")),
        (r"^## 4\. ", dict(target=PC + "d{RUN}")), (r"^## 5\. ", dict(target=PC + "e{RUN}")),
        (r"^## 6\. ", dict(target=PC + "f{RUN}")), (r"^## 7\. ", dict(target=PC + "g{RUN}")),
        (r"^## The outbox", dict(target=PC + "b{RUN}"))]),
    "06-feeds": dict(target=PC + "q{RUN}"),
    "07-curl": dict(target="billing/invoices/inv-{RUN}", cmd_pace=1.6),
    "08-ownership": dict(target="rx{RUN}/rules/R2", follow=[
        (r"^## 1\. ", dict(target="rx{RUN}/rules/R1")), (r"^## 2\. ", dict(target="rx{RUN}/rules/R2")),
        (r"^## 3\. ", dict(target="rx{RUN}/hooks/freeze_account")), (r"^## 4\. ", dict(target="rx{RUN}/rules/R2")),
        (r"^## 5\. ", dict(target="rx{RUN}/signals/velocity_24h")), (r"^## 6\. ", dict(target="rx{RUN}/rules/R1")),
        (r"^## 7\. ", dict(target="rx{RUN}/signals/velocity_24h")),
        (r"^## Nobody ratifies", dict(target="rx{RUN}/signals/geo_mismatch")),
        (r"^## What was said", dict(target="rx{RUN}/signals/velocity_24h"))]),
    "09-revisions-checks-agents": dict(target=PC + "r{RUN}", follow=[
        (r"thread show (T-\S+)", dict(thread=r"\1")),
        (r"^## 6\. ", dict(target=PC + "k{RUN}")), (r"^## The trail", dict(target=PC + "r{RUN}"))]),
}

PAGE = """<!doctype html><meta charset=utf-8><style>
:root{--bg:#0b0e11;--bg2:#12161b;--line:#222a33;--fg:#d7dde3;--dim:#6b7580}
*{box-sizing:border-box}html,body{margin:0;height:100%;background:var(--bg);color:var(--fg);font:12px/1.3 ui-monospace,SFMono-Regular,Menlo,monospace;overflow:hidden}
#g{display:grid;grid-template-columns:__TW__px 1fr;height:100%}
.pane{display:flex;flex-direction:column;min-width:0;min-height:0}.pane+.pane{border-left:1px solid var(--line)}
.bar{background:var(--bg2);border-bottom:1px solid var(--line);padding:2px 8px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:var(--dim);flex:none}
.bar b{color:#58a6ff;font-weight:700}.bar i{font-style:normal;color:var(--fg)}
#t{flex:1;margin:0;padding:4px 8px;overflow:hidden;white-space:pre-wrap;word-break:break-all;font:inherit}
#t .hd{color:#39c5cf;font-weight:700}#t .cmd{color:#d29922}#t .dim{color:var(--dim)}#t .bad{color:#f85149}#t .b{font-weight:700}
iframe{flex:1;border:0;width:100%;background:var(--bg)}
</style><div id=g>
<div class=pane><div class=bar><b>TERMINAL</b> <i id=cmd></i> <span id=clk></span></div><pre id=t></pre></div>
<div class=pane><div class=bar><b>CONSOLE</b> <i id=url></i></div><iframe id=f></iframe></div>
</div><script>
const T = document.getElementById('t');
function put(h) { T.insertAdjacentHTML('beforeend', h); T.scrollTop = T.scrollHeight; }
function set(id, s) { document.getElementById(id).textContent = s; }
</script>"""

SGR = {"1;36": "hd", "33": "cmd", "2": "dim", "31": "bad", "1": "b", "1;35": "hd"}
ANSI = re.compile(r"\x1b\[([0-9;]*)m")


def ansi_html(line):
    out, pos, open_ = [], 0, False
    for m in ANSI.finditer(line):
        out.append(html.escape(line[pos:m.start()])); pos = m.end()
        if open_: out.append("</span>"); open_ = False
        cls = SGR.get(m.group(1))
        if cls: out.append(f"<span class={cls}>"); open_ = True
    out.append(html.escape(line[pos:]))
    if open_: out.append("</span>")
    return "".join(out)


def ffmpeg_exe():
    exe = shutil.which("ffmpeg")
    if exe: return exe
    try:
        import imageio_ffmpeg; return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        sys.exit("no ffmpeg: put one on PATH or `pip install imageio-ffmpeg`")


def record(browser, name, a):
    cfg = DEMOS[name]; run_id = "v" + time.strftime("%H%M%S"); user = cfg.get("user", "alice")
    fmt = lambda s: s.replace("{RUN}", run_id)
    frames = Path(tempfile.mkdtemp(prefix=f"tlc-rec-{name}-"))
    ctx = browser.new_context(color_scheme="dark", viewport={"width": W, "height": H}, device_scale_factor=1)
    page = ctx.new_page(); page.set_content(PAGE.replace("__TW__", str(TERM_W)))
    page.evaluate("s => set('cmd', s)", f"$ RUN={run_id} scripts/demos/{name}.sh")
    state = {"target": None, "con": None}

    def show(target):
        state["target"] = target
        page.evaluate("s => set('url', s)", f"{a.url}/?user={user}#target={target}")
        if state["con"] is None:
            page.evaluate("u => { document.getElementById('f').src = u; }", f"{a.url}/?user={user}#target={target}")
            page.frame_locator("#f").locator("#c3 table").first.wait_for(timeout=15000)
            state["con"] = page.query_selector("#f").content_frame()
        else:
            state["con"].evaluate("t => { location.hash = 'target=' + encodeURIComponent(t); }", target)

    def nudge(thread=None):
        con = state["con"]
        if thread: con.evaluate("t => { panel.openThread(t); }", thread)
        con.evaluate("() => { tick(); panel.refresh(); }")

    show(fmt(cfg["target"]))
    env = dict(os.environ, TLC_URL=a.url, RUN=run_id, TLC_DEMO_PACE=str(cfg.get("pace", a.pace)), TLC_DEMO_CMD_PACE=str(cfg.get("cmd_pace", a.cmd_pace)), PYTHONUNBUFFERED="1")
    proc = subprocess.Popen(["bash", str(ROOT / "scripts/demos" / f"{name}.sh")], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, text=True, bufsize=1, errors="replace")
    q = queue.Queue()

    def pump():
        for l in proc.stdout: q.put(l)
        q.put(None)
    threading.Thread(target=pump, daemon=True).start()

    shots, last, t0, done_at, eof = [], None, time.monotonic(), None, False
    while True:
        loop = time.monotonic(); lines = []
        while not eof:
            try: l = q.get_nowait()
            except queue.Empty: break
            if l is None: eof = True
            else: lines.append(l.rstrip("\n"))
        if lines:
            page.evaluate("h => put(h)", "".join(ansi_html(l) + "\n" for l in lines)); thread = None
            for l in lines:
                plain = ANSI.sub("", l)
                for rx, act in cfg.get("follow", []):
                    m = re.search(rx, plain)
                    if not m: continue
                    if "target" in act: show(m.expand(fmt(act["target"])))
                    if "thread" in act: thread = m.expand(act["thread"])
            nudge(thread)
        if eof and done_at is None: done_at = loop; nudge()
        page.evaluate("s => set('clk', s)", f"t+{int(loop - t0):>3}s" + (f"  exit {proc.wait()}" if eof else ""))
        png = page.screenshot(type="png"); now = time.monotonic() - t0; h = hashlib.sha1(png).digest()
        if h != last:
            f = frames / f"{len(shots):05d}.png"; f.write_bytes(png); shots.append((now, f)); last = h
        if done_at is not None and loop - done_at > a.tail: break
        time.sleep(max(0.0, 0.125 - (time.monotonic() - loop)))
    end = time.monotonic() - t0; rc = proc.wait(); ctx.close()

    out = Path(a.out) / f"{name}.mp4"; out.parent.mkdir(parents=True, exist_ok=True)
    lst = frames / "frames.txt"
    with open(lst, "w") as fh:
        for i, (t, f) in enumerate(shots):
            nxt = shots[i + 1][0] if i + 1 < len(shots) else end
            fh.write(f"file '{f}'\nduration {max(nxt - t, 0.01):.3f}\n")
        fh.write(f"file '{shots[-1][1]}'\n")
    subprocess.run([ffmpeg_exe(), "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(lst), "-vf", f"fps={a.fps}", "-c:v", "libx264",
                    "-preset", "veryslow", "-crf", str(a.crf), "-tune", "animation", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out)], check=True)
    if not a.keep_frames: shutil.rmtree(frames)
    print(f"{name:<28} exit={rc} {end:6.1f}s {len(shots):>4} frames {out.stat().st_size / 1e6:5.2f} MB  {out}" + (f"  frames: {frames}" if a.keep_frames else ""), flush=True)
    return rc


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("demos", nargs="*", help="prefixes, e.g. 05 09 (default: all)")
    ap.add_argument("--url", default=os.environ.get("TLC_URL", "http://localhost:8790"))
    ap.add_argument("--out", default=str(ROOT / "docs/recordings"))
    ap.add_argument("--pace", type=float, default=1.6, help="seconds held at each ## step header")
    ap.add_argument("--cmd-pace", type=float, default=0.8, help="seconds held after each command")
    ap.add_argument("--tail", type=float, default=3.0, help="seconds held on the final state")
    ap.add_argument("--fps", type=int, default=8); ap.add_argument("--crf", type=int, default=26)
    ap.add_argument("--keep-frames", action="store_true", help="leave the PNG frames in a temp dir and print where")
    a = ap.parse_args(); a.url = a.url.rstrip("/")
    names = [n for n in DEMOS if not a.demos or any(n.startswith(p) for p in a.demos)]
    if not names: sys.exit(f"no demo matches {a.demos}; have {', '.join(DEMOS)}")
    ffmpeg_exe(); bad = 0
    with sync_playwright() as p:
        b = p.chromium.launch(channel="chrome", headless=True)
        for n in names: bad += record(b, n, a) != 0
        b.close()
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
