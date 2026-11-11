#!/usr/bin/env python3
"""Click-through smoke test of the iframe widget and the console against a running, seeded TLC.

Needs Playwright + a local Chrome:  python scripts/ui_smoke.py [iframe.png console.png]
(on this Mac: ~/bankcli/.venv/bin/python scripts/ui_smoke.py). Run scripts/seed.sh first: it uses AR-6392.
"""
import sys, time
from playwright.sync_api import sync_playwright
U = "http://localhost:8790"; E = f"sentinel/proposed_changes/ui{int(time.time())}"
errs = []
with sync_playwright() as p:
    b = p.chromium.launch(channel="chrome", headless=True)
    def page(user):
        pg = b.new_context(color_scheme="dark", viewport={"width": 1000, "height": 900}).new_page()
        pg.on("console", lambda m: errs.append(f"{user}: {m.text}") if m.type == "error" else None)
        pg.on("pageerror", lambda e: errs.append(f"{user}: PAGEERROR {e}"))
        pg.goto(f"{U}/iframe?target={E}&user={user}&interval=2000"); pg.wait_for_selector("h2"); return pg
    a = page("alice")
    assert "not yet known" in a.inner_text("#p")
    a.fill("form[data-form=tag] input[name=tag]", "ui-test"); a.press("form[data-form=tag] input[name=tag]", "Enter")
    a.wait_for_selector("button[data-act=untag]"); print("tag ok")
    a.fill("form[data-form=thread] input[name=title]", "ui thread"); a.click("form[data-form=thread] button")
    a.wait_for_selector("form[data-form=comment] input"); print("thread ok")
    a.fill("form[data-form=comment] input", "first!"); a.press("form[data-form=comment] input", "Enter")
    a.wait_for_selector("a[data-act=reply]"); print("comment ok")
    a.select_option("form[data-form=request] select", "AR-6392"); a.fill("form[data-form=request] input[name=context]", "ui test"); a.click("form[data-form=request] button")
    a.wait_for_selector("td.PENDING"); assert a.locator("button[data-act=approve]").count() == 0; print("request ok; requester has no approve button")
    bob = page("bob")
    bob.click("tr[data-go^='thread:']"); bob.wait_for_selector("a[data-act=reply]")
    bob.click("a[data-act=like]"); bob.wait_for_selector("a[data-act=unlike]"); print("like ok")
    bob.fill("form[data-form=claim] input[name=aspect]", "availability"); bob.fill("form[data-form=claim] input[name=owner]", "trust-infra"); bob.click("form[data-form=claim] button")
    bob.wait_for_selector("span.OWNED"); print("ownership claim ok")
    bob.click("a[data-act=release]"); bob.wait_for_selector("span.OWNED", state="detached"); print("ownership release ok")
    bob.click("a[data-act=reply]"); bob.fill("form[data-parent] input", "a reply"); bob.press("form[data-parent] input", "Enter")
    bob.wait_for_function("document.querySelectorAll('.cm .tx').length >= 3"); print("reply ok")
    bob.click("button[data-act=approve]"); bob.wait_for_selector("button[data-act=unapprove]"); print("bob approved (1/2)")
    carol = page("carol"); carol.click("button[data-act=approve]"); carol.wait_for_selector("td.APPROVED"); print("carol approved -> APPROVED")
    a.wait_for_selector("td.APPROVED", timeout=8000); print("alice's panel caught up by polling")
    a.click("button[data-act=execute]"); a.wait_for_selector("td.EXECUTED"); print("execute ok")
    mal = page("mallory"); mal.wait_for_selector("td.EXECUTED")
    a.screenshot(path=(sys.argv + ["build/ui-iframe.png", "build/ui-console.png"])[1], full_page=True)
    c = b.new_context(color_scheme="dark", viewport={"width": 1680, "height": 1000}).new_page()
    c.on("pageerror", lambda e: errs.append(f"console: PAGEERROR {e}")); c.on("console", lambda m: errs.append(f"console: {m.text}") if m.type == "error" else None)
    c.goto(f"{U}/?user=traviscj#target={E}"); c.wait_for_selector("#c3 table"); c.wait_for_selector("#panel h2")
    c.on("dialog", lambda d: d.dismiss())
    c.fill("form[data-form=member] input[name=group]", "trust-ui"); c.fill("form[data-form=member] input[name=ldap]", "zed"); c.click("form[data-form=member] button")
    c.wait_for_function("document.querySelector('#c1').innerText.includes('trust-ui')"); print("console: directory add ok")
    c.click("#c3 tr[data-go]"); c.wait_for_function("location.hash.includes('target=')"); print("console: row nav ok")
    c.click("a[data-act=unmember][data-g=trust-ui]"); c.wait_for_function("!document.querySelector('#c1').innerText.includes('trust-ui')"); print("console: directory remove ok")
    c.screenshot(path=(sys.argv + ["build/ui-iframe.png", "build/ui-console.png"])[2])
    b.close()
print("console errors:", errs or "none")
sys.exit(1 if errs else 0)
