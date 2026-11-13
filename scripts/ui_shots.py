#!/usr/bin/env python3
"""Regenerates the screenshots under docs/img from a running, freshly seeded TLC (scripts/seed.sh).

  TLC_URL=http://localhost:8790 python scripts/ui_shots.py [outdir=docs/img]      (needs Playwright + a local Chrome)

One PNG per panel of the per-record widget, cropped to that panel, plus the console and its rules table.
"""
import os, sys, json, urllib.request
from playwright.sync_api import sync_playwright
U = os.environ.get("TLC_URL", "http://localhost:8790"); OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/img"
PC, VEL, SIG, ATO = "sentinel/proposed_changes/1234", "sentinel/rules/VEL_2", "sentinel/signals/velocity_24h", "sentinel/rules/ATO_VELOCITY"

def api(path, user="alice"):
    return json.load(urllib.request.urlopen(urllib.request.Request(U + path, headers={"X-TLC-User": user})))

thread = next(t["token"] for t in api(f"/api/summary?target={PC}")["threads"] if (t.get("target") or "").startswith("AP-"))
with sync_playwright() as p:
    b = p.chromium.launch(channel="chrome", headless=True)
    def panel(target, user, width=1040, extra=""):
        pg = b.new_context(color_scheme="dark", viewport={"width": width, "height": 900}, device_scale_factor=2).new_page()
        pg.goto(f"{U}/iframe?target={target}&user={user}&interval=600000{extra}"); pg.wait_for_selector("section[data-panel]"); pg.wait_for_timeout(400); return pg
    def shot(pg, name, sel): pg.locator(sel).screenshot(path=f"{OUT}/{name}.png"); print(name)
    pg = panel(PC, "dave", extra=f"&thread={thread}")
    shot(pg, "approvals", "section[data-panel=approvals]")       # @4 waiting on a group and a check; @3's approval dismissed
    shot(pg, "comments", "section[data-panel=comments]")         # a thread on the request, comments stamped @3 / @4, an agent among them
    shot(pg, "tags", "section[data-panel=tags]")
    shot(pg, "logs", "section[data-panel=logs]")
    pg.screenshot(path=f"{OUT}/iframe.png", full_page=True); print("iframe")
    shot(panel(VEL, "olga"), "impacts", "section[data-panel=approvals]")       # declared impacts: asked / told / skipped
    shot(panel(SIG, "olga"), "break-glass", "section[data-panel=approvals]")   # ACTED, reverting unless ratified
    shot(panel(ATO, "bob"), "ownership-panel", "section[data-panel=ownership]")
    c = b.new_context(color_scheme="dark", viewport={"width": 1680, "height": 1050}, device_scale_factor=2).new_page()
    c.goto(f"{U}/?user=traviscj#target={PC}"); c.wait_for_selector("#c3 table"); c.wait_for_selector("#panel h2"); c.wait_for_timeout(600)
    c.screenshot(path=f"{OUT}/console.png"); print("console")
    # the rules table: the h2 and the table + admin form that follow it
    box = c.evaluate("""() => { const h = [...document.querySelectorAll('h2')].find(x => x.textContent.startsWith('approval rules')); const r = h.getBoundingClientRect();
      let el = h.nextElementSibling, bottom = r.bottom, right = r.right; for (let i = 0; i < 2 && el; i++, el = el.nextElementSibling) { const q = el.getBoundingClientRect(); bottom = Math.max(bottom, q.bottom); right = Math.max(right, q.right); }
      return {x: r.left, y: r.top, width: right - r.left, height: bottom - r.top}; }""")
    c.screenshot(path=f"{OUT}/rules.png", clip=box); print("rules")
    b.close()
