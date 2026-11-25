#!/usr/bin/env python3
"""Claude Code hook: keep TLC's changelog and open-loops queue fed from every session.

  Stop / SessionEnd   (no args; --flush on SessionEnd)
      Reads what was appended to the transcript since last time and counts the mechanical facts: commits,
      pushes, deploys and restarts (with the one-line description the command was run under), files edited
      per repo. Posts them as one TLC log entry (source claude.session) at most every POST_EVERY_S, and at
      session end. No model involved; nothing is posted for a session that changed nothing.
  UserPromptSubmit    (--remind)
      When the session has done work and has not touched the queue (`tlc item …`) for REMIND_EVERY_S, injects
      a note asking for an open-loops checkpoint.

Needs ~/.config/tlc/url and ~/.config/tlc/token (and optionally ~/.config/tlc/user: whose queue this is, when the
login name is not theirs); without them it does nothing. Never blocks or fails a turn.
State: ~/.cache/tlc-loops/<session>.json
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.request
from pathlib import Path

POST_EVERY_S = 600
REMIND_EVERY_S = 1800
STATE = Path.home() / ".cache" / "tlc-loops"
HOME = str(Path.home())
# things that change what is running somewhere: a real rsync to a host, a deploy script being run (not read),
# a container or unit being (re)started or rebuilt
DEPLOY_RE = re.compile(r"\brsync\s+(?!-\w*n\b)(?!.*--dry-run)[^|;&]*\s\S+:\S*|(?:^|&&|;|\|\|)\s*(?:sudo )?(?:sh |bash )?\S*deploy\.sh\b"
                       r"|docker compose up|docker restart|systemctl (?:re)?start|docker build", re.M)
REMIND = (
    "Open-loops checkpoint (automatic; it has been over 30 minutes of work since this session last updated the queue). "
    "Before you finish this turn, record what this session has left waiting on Travis and what it has resolved: "
    "`~/.local/bin/tlc item add \"<title>\" --kind question|todo|deploy|commit|idea --source claude --key <stable-key> [--note <detail>]` "
    "for each thing still open (a question you asked that is unanswered, a change that is undeployed, uncommitted or "
    "unpushed, a path you could not test), and `~/.local/bin/tlc item done <I-token>` for any now settled (`~/.local/bin/tlc item ls --source claude` "
    "lists them). Reuse a key to update an item instead of adding a duplicate. If nothing has changed, do nothing. "
    "Do this quietly: it is bookkeeping, not part of your reply."
)


def conf(name: str) -> str:
    try:
        return (Path.home() / ".config" / "tlc" / name).read_text().splitlines()[0].strip()
    except (OSError, IndexError):
        return ""


def repo_of(path: str) -> str:
    """~/inforad/ops/x.py -> inforad; anything outside the home directory -> its top-level directory."""
    p = os.path.expanduser(path).rstrip("/")
    if p == HOME:
        return "~"
    if p.startswith(HOME + "/"):
        first = p[len(HOME) + 1:].split("/")[0]
        return first if first and not first.startswith(".") else "~"
    return "/" + p.strip("/").split("/")[0] if p.startswith("/") else "~"


def scan(transcript: str, offset: int, pend: dict) -> tuple[int, float]:
    """Fold transcript lines from byte `offset` into `pend`. Returns (new offset, time of the last `tlc item` call seen)."""
    checkpoint = 0.0
    try:
        f = open(transcript, "rb")
    except OSError:
        return offset, checkpoint
    with f:
        f.seek(offset)
        while True:
            line = f.readline()
            if not line.endswith(b"\n"):            # a partial last line: read it next time
                break
            offset = f.tell()
            try:
                d = json.loads(line)
            except ValueError:
                continue
            if d.get("type") != "assistant" or d.get("isSidechain"):
                continue
            for c in (d.get("message") or {}).get("content") or []:
                if not isinstance(c, dict) or c.get("type") != "tool_use":
                    continue
                inp = c.get("input") or {}
                if c.get("name") in ("Edit", "Write", "NotebookEdit") and inp.get("file_path"):
                    r = repo_of(inp["file_path"])
                    files = pend.setdefault("edited", {}).setdefault(r, [])
                    if inp["file_path"] not in files:
                        files.append(inp["file_path"])
                elif c.get("name") == "Bash":
                    cmd = inp.get("command") or ""
                    m = re.search(r"\bcd ((?:~|" + re.escape(HOME) + r")/[^\s;&|]+)", cmd)     # a local cd; one inside an ssh command is not ours
                    r = repo_of(m.group(1) if m else d.get("cwd") or "~")
                    n = len(re.findall(r"\bgit (?:-C \S+ )?commit\b", cmd))
                    if n:
                        pend.setdefault("commits", {})[r] = pend.setdefault("commits", {}).get(r, 0) + n
                    if re.search(r"\bgit (?:-C \S+ )?push\b", cmd):
                        pend.setdefault("pushes", {})[r] = pend.setdefault("pushes", {}).get(r, 0) + 1
                    if DEPLOY_RE.search(cmd) and inp.get("description"):
                        pend.setdefault("deploys", []).append(inp["description"][:140])
                    if re.search(r"\btlc item (add|mk|done|drop|snooze|reopen|sync)\b", cmd):
                        checkpoint = time.time()
    return offset, checkpoint


def message(session: str, cwd: str, pend: dict) -> str:
    import socket
    parts = [f"session {session[:8]} on {socket.gethostname().split('.')[0]} in {repo_of(cwd) if cwd else '~'}"]
    if pend.get("commits"):
        parts.append("commits " + " ".join(f"{r}×{n}" for r, n in sorted(pend["commits"].items())))
    if pend.get("pushes"):
        parts.append("pushed " + " ".join(sorted(pend["pushes"])))
    if pend.get("edited"):
        parts.append("edited " + " ".join(f"{r}:{len(f)}" for r, f in sorted(pend["edited"].items())))
    if pend.get("deploys"):
        d = pend["deploys"]
        parts.append("deploys/restarts: " + "; ".join(d[:8]) + (f"; +{len(d) - 8} more" if len(d) > 8 else ""))
    return " · ".join(parts)


def post(msg: str) -> bool:
    url, tok = conf("url"), conf("token")
    if not url or not tok:
        return False
    req = urllib.request.Request(url.rstrip("/") + "/api/logs", method="POST",
                                 data=json.dumps({"message": msg, "source": "claude.session", "level": "INFO"}).encode(),
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + tok, "X-TLC-User": conf("user") or os.environ.get("USER", "")})
    try:
        with urllib.request.urlopen(req, timeout=4) as r:
            return r.status == 200
    except Exception:
        return False


def main() -> int:
    try:
        hook = json.load(sys.stdin)
    except ValueError:
        return 0
    sid, transcript = hook.get("session_id") or "", hook.get("transcript_path") or ""
    if not sid or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", sid):
        return 0
    STATE.mkdir(parents=True, exist_ok=True)
    sf = STATE / f"{sid}.json"
    try:
        st = json.loads(sf.read_text())
    except (OSError, ValueError):
        st = {"offset": 0, "last_post": 0.0, "checkpoint": time.time(), "reminded": 0.0, "worked": False, "pend": {}}
    now = time.time()
    if transcript:
        st["offset"], cp = scan(transcript, st["offset"], st["pend"])
        st["checkpoint"] = max(st["checkpoint"], cp)
    if st["pend"]:
        st["worked"] = True
    if "--remind" in sys.argv:
        if st["worked"] and now - st["checkpoint"] >= REMIND_EVERY_S and now - st["reminded"] >= REMIND_EVERY_S and conf("url"):
            st["reminded"] = now
            print(json.dumps({"hookSpecificOutput": {"hookEventName": "UserPromptSubmit", "additionalContext": REMIND}}))
    elif st["pend"] and ("--flush" in sys.argv or now - st["last_post"] >= POST_EVERY_S):
        if post(message(sid, hook.get("cwd") or "", st["pend"])):
            st["pend"], st["last_post"] = {}, now
    tmp = sf.with_suffix(".tmp")
    tmp.write_text(json.dumps(st))
    tmp.replace(sf)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:           # a bookkeeping hook must never cost a turn
        sys.exit(0)
