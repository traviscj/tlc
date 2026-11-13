// Shared by the console and the embeddable iframe: API client, formatters, and the per-target panel.
const TLC = (() => {
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
  const qs = new URLSearchParams(location.search);
  const store = { get: k => { try { return localStorage.getItem(k) } catch (e) { return null } }, set: (k, v) => { try { localStorage.setItem(k, v) } catch (e) {} } };
  let user = qs.get('user') || store.get('tlc_user') || '';
  const setUser = u => { user = (u || '').trim().toLowerCase(); store.set('tlc_user', user); };

  async function api(method, path, body) {
    const h = {};
    if (user) h['X-TLC-User'] = user;
    const hasBody = method !== 'GET' && method !== 'DELETE';
    if (hasBody) h['Content-Type'] = 'application/json';
    const r = await fetch(path, {method, headers: h, body: hasBody ? JSON.stringify(body ?? {}) : undefined, signal: AbortSignal.timeout(8000)});
    const text = await r.text();
    if (!r.ok) throw new Error(`${r.status} ${text.slice(0, 400)}`);
    return text.startsWith('{') ? JSON.parse(text) : {};
  }
  const q = o => Object.entries(o).filter(([, v]) => v !== undefined && v !== null && v !== '').map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');

  const pad = n => String(n).padStart(2, '0');
  // HH:MM:SS local for today, MM-DD HH:MM otherwise; full ISO on hover.
  function ts(iso) {
    if (!iso) return '<span class=dim>·</span>';
    const d = new Date(iso), now = new Date();
    const same = d.toDateString() === now.toDateString();
    const s = same ? `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}` : `${d.getFullYear() !== now.getFullYear() ? d.getFullYear() + '-' : ''}${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
    return `<span title="${esc(iso)}">${s}</span>`;
  }
  function age(iso) {
    if (!iso) return '';
    let s = Math.round((Date.now() - new Date(iso)) / 1000);
    const f = s < 0; s = Math.abs(s);
    const t = s < 90 ? s + 's' : s < 5400 ? Math.round(s / 60) + 'm' : s < 129600 ? Math.round(s / 3600) + 'h' : Math.round(s / 86400) + 'd';
    return f ? '+' + t : t;
  }
  // cols: [header, fn(row)->html, class?]
  function table(cols, rows, opt = {}) {
    if (!rows.length) return `<div class=dim>&nbsp;${esc(opt.empty || 'none')}</div>`;
    return `<table><thead><tr>${cols.map(c => `<th class="${c[2] || ''}">${c[0]}</th>`).join('')}</tr></thead><tbody>${rows.map(r =>
      `<tr${opt.go ? ` data-go="${esc(opt.go(r))}"` : ''}${opt.cls ? ` class="${opt.cls(r) || ''}"` : ''}>${cols.map(c => `<td class="${c[2] || ''}">${c[1](r)}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
  }

  // ---------------------------------------------------------------- target panel
  // Everything TLC knows about one target. opts.panels ⊆ ownership,approvals,tags,comments,logs
  function panel(root, opts = {}) {
    const st = {target: opts.target || '', panels: opts.panels || ['ownership', 'approvals', 'tags', 'comments', 'logs'], open: null, reply: null, edit: null, level: '', err: '', data: null, rules: []};
    const has = p => st.panels.includes(p);

    async function load() {
      if (!st.target) { st.data = null; return; }
      const t = st.target;
      const [sum, hist, rules] = await Promise.all([
        api('GET', `/api/summary?${q({target: t, log_limit: 40})}`),
        has('tags') ? api('GET', `/api/taggings?${q({target: t, limit: 8})}`) : {taggings: []},
        has('approvals') ? api('GET', '/api/approval-rules') : {rules: []},
      ]);
      const views = has('approvals') ? await Promise.all(sum.approvables.slice(0, 6).map(a => api('GET', `/api/approvables/${a.token}`))) : [];
      const thread = has('comments') && st.open ? await api('GET', `/api/threads/${st.open}`).catch(() => null) : null;
      // 404 until the record exists; that just means "no graph yet".
      const aff = has('ownership') && sum.known ? await api('GET', `/api/ownership/affected?${q({target: t})}`).catch(() => null) : null;
      if (t !== st.target) return; // navigated away mid-flight
      st.data = {sum, hist: hist.taggings, views, thread, aff};
      st.rules = rules.rules;
    }

    function approvals(d) {
      const rows = d.views.map(v => {
        const a = v.approvable, e = v.evaluation, y = v.you || {};
        const groups = e.groups.map(g => `<span class="chip ${g.satisfied_by ? 'ok' : 'dim'}" title="${esc(g.directory_group)}${g.admins_only ? ' (admins only)' : ''}; candidates: ${esc(g.candidates.join(', ') || 'none')}">${esc(g.group)}${g.satisfied_by ? '✓' + esc(g.satisfied_by) : '·'}</span>`).join('');
        const live = v.approvals.filter(x => !x.unapproved_at), mine = live.some(x => x.approved_by === user);
        const rv = x => x.revision ? '@' + esc(x.revision) : '#' + x.seq;
        const revs = v.revisions || [], gone = v.approvals.filter(x => x.superseded);
        // Every revision the request has been through; the last one is what is being decided.
        const history = revs.length > 1 ? `<br><span class=dim>revisions:</span> ` + revs.map((x, i) => `<span class="chip ${i === revs.length - 1 ? 'b' : 'dim'}" title="${esc(x.revised_by)} · ${esc(x.created_at)}${x.context ? ' · ' + esc(x.context) : ''}">${rv(x)}${x.dismissed ? ` <span class=warn>−${x.dismissed}</span>` : ''}</span>`).join('<span class=dim>→</span>') + (gone.length ? ` <span class=dim>dismissed: ${gone.map(x => esc(x.approved_by) + (x.revision ? '@' + esc(x.revision) : '')).join(' ')}</span>` : '') : '';
        const acted = a.approval_state === 'ACTED';
        const open = a.approval_state === 'PENDING' || a.approval_state === 'APPROVED' || acted;
        const parties = (v.parties || []).map(p => `<span class="chip ${p.mode === 'CONSENT' ? '' : 'dim'}" title="${esc(p.mode)} · ${esc(p.status)} via ${esc(p.source)} · ${esc(p.via.join(', '))}">${p.mode === 'CONSENT' ? 'ask' : 'tell'} ${esc(p.directory_group)}<span class=dim>:${esc(p.aspects.join(','))}</span>${p.status !== 'OWNED' && p.status !== 'STAKEHOLDER' ? ` <span class=${p.status}>${p.status}</span>` : ''}</span>`).join('');
        const fmt = x => x == null ? '?' : String(+x);
        const impacts = (v.impacts || []).map(i => `<span class="chip ${i.effect === 'CONSENT' ? '' : 'dim'}" title="${esc(i.reason)}${i.threshold != null ? ' · threshold ' + fmt(i.threshold) : ''}${i.note ? ' · ' + esc(i.note) : ''}">${esc(i.aspect)} <span class="${!i.declared || i.magnitude == null ? 'warn' : 'b'}">${i.declared ? fmt(i.magnitude) : 'undeclared'}</span>${i.unit ? ' ' + esc(i.unit) : ''}${i.rule_mode && i.rule_mode !== i.effect ? ` <span class=warn>${i.effect === 'SKIPPED' ? 'not told' : 'told, not asked'}</span>` : ''}</span>`).join('');
        const ckCls = {PASS: 'ok', FAIL: 'bad', PENDING: 'warn', MISSING: 'dim'};
        const checks = (e.checks || []).map(k => `<span class="chip ${k.required ? '' : 'dim'}" title="${esc(k.description || k.name)}${k.bound ? ' · allowed ' + esc(k.bound) : ''}${k.reported_by ? ' · ' + esc(k.reported_by) + ' ' + esc(k.reported_at) : ' · nothing reported for this revision'}${k.summary ? ' · ' + esc(k.summary) : ''}${k.required ? '' : ' · not required by the rule'}">${k.url ? `<a href="${esc(k.url)}" target=_blank rel=noopener>${esc(k.name)}</a>` : esc(k.name)} <span class="${ckCls[k.status]} b">${k.status === 'MISSING' ? '…' : k.status}</span>${k.value != null ? ` <span class=b>${fmt(k.value)}</span>${k.unit ? esc(k.unit) : ''}` : ''}${k.bound ? `<span class=dim> ∈ ${esc(k.bound)}</span>` : ''}</span>`).join('');
        const glass = a.mode === 'RATIFY' ? `<br><span class="${acted ? 'bad' : 'dim'}">break-glass by ${esc(a.requested_by)}${a.acting_group ? ' for ' + esc(a.acting_group) : ''}: already done${acted ? `; reverts in ${age(a.expires_at).replace('+', '')} (${ts(a.expires_at)}) unless ratified` : ''}</span>` : '';
        const btn = [
          open && y.allowed && !mine ? `<button class=go data-act=approve data-id="${a.token}" data-rev="${esc(a.revision || '')}" title="${esc(y.reason)}${a.revision ? ' · you are approving @' + esc(a.revision) : ''}">${acted ? 'ratify' : 'approve'}</button>` : '',
          open && mine ? `<button data-act=unapprove data-id="${a.token}">unapprove</button>` : '',
          a.approval_state === 'APPROVED' ? `<button class=go data-act=execute data-id="${a.token}" data-rev="${esc(a.revision || '')}">execute</button>` : '',
          open ? `<button class=no data-act=decline data-id="${a.token}">${acted ? 'revert' : 'decline'}</button>` : '',
        ].join('');
        return `<tr><td class="${a.approval_state} b">${a.approval_state}</td><td>${esc(a.token)}${a.revision ? `<span class="info b" title="revision ${esc(a.revision)} of ${esc(a.target)} is what is up for approval">@${esc(a.revision)}</span>` : ''}</td><td title="${esc(v.rule.description)}">${esc(a.approval_rule_token)} <span class=dim>${esc(e.requirement)}</span></td><td class=n>${e.have}/${e.needed}</td><td>${esc(a.requested_by)}${a.requested_via_service ? `<span class=dim>@${esc(a.requested_via_service)}</span>` : ''}</td><td class=n>${age(a.created_at)}</td><td>${btn}</td></tr>
          <tr><td></td><td colspan=6 class=w>${groups}<span class=dim>${esc(e.summary)}</span>${parties ? '<br>' + parties : ''}${checks ? '<br><span class=dim>checks:</span> ' + checks : ''}${impacts ? '<br><span class=dim>impact:</span> ' + impacts : ''}${glass}${live.length ? ' · ' + live.map(x => `<span class=ok>${esc(x.approved_by)}</span>${x.agent ? `<span class=acc title="an agent${x.operator ? ', operated by ' + esc(x.operator) : ''}${v.rule.agent_approvals === 'ALLOW' ? '' : '; ' + esc(v.rule.token) + ' counts people only'}">⚙${esc(x.operator || '')}</span>` : ''}${x.via_groups.length ? `<span class=dim>(${esc(x.via_groups.join(','))})</span>` : ''}${x.stale ? `<span class=warn title="given at an earlier revision; ${esc(v.rule.token)} keeps approvals across revisions">${x.revision ? '@' + esc(x.revision) : 'stale'}</span>` : ''}`).join(' ') : ''}${history}${a.approval_context ? `<br><span class=dim>why:</span> ${esc(a.approval_context)}` : ''}${a.decline_reason ? `<br><span class=bad>declined by ${esc(a.declined_by)}:</span> ${esc(a.decline_reason)}` : ''}${!y.allowed && open && y.reason ? `<br><span class=dim>you can't approve: ${esc(y.reason)}</span>` : ''}</td></tr>`;
      }).join('');
      return `<h2>approvals <small>${d.sum.approvables.length}</small></h2>${rows ? `<table><thead><tr><th>state</th><th>approvable</th><th>rule</th><th class=n>have/need</th><th>requested by</th><th class=n>age</th><th></th></tr></thead><tbody>${rows}</tbody></table>` : ''}
        <form class=row data-form=request><select name=rule>${st.rules.map(r => `<option value="${esc(r.token)}">${esc(r.token)} ${esc(r.requirement)} — ${esc(r.description.slice(0, 40))}</option>`).join('')}</select><input class=g name=context placeholder="why should someone approve this?"><label title="break-glass: you have ALREADY done it; it reverts unless ratified in time"><input type=checkbox name=ratify style="height:auto">already done</label><button>request approval</button></form>`;
    }

    // `~` = inherited from the type-wide default; a holder nobody re-affirmed in lapse_days shows its age in days.
    const holder = h => `<span class="${h.lapsed ? 'warn' : ''}" title="${esc(h.role)} since ${esc(h.since)}; affirmed ${esc(h.affirmed_at)} by ${esc(h.by || '?')}; ${h.source === 'TYPE' ? 'inherited from the type default' : 'claimed on this record'}">${esc(h.owner)}${h.source === 'TYPE' ? '~' : ''}${h.lapsed ? `(${h.age_days}d)` : ''}</span>`;
    function ownership(d) {
      const cells = d.sum.ownership || [], bad = cells.filter(c => c.status !== 'OWNED').length;
      const rel = (c, h) => h.source === 'DIRECT' ? ` <a href=# data-act=release data-id="${esc(c.aspect)}" data-owner="${esc(h.owner)}" title="release (you must be in ${esc(h.owner)})">×</a>` : '';
      const tbl = table([['aspect', c => `<span class=b title="${esc(c.description || '')}">${esc(c.aspect)}</span>`], ['status', c => `<span class=${c.status}>${c.status}</span>`], ['owner', c => c.owners.map(h => holder(h) + rel(c, h)).join(' ') || '<span class=dim>—</span>', 'w'], ['stakeholders', c => c.stakeholders.map(h => holder(h) + rel(c, h)).join(' '), 'w'], ['since', c => c.owners.length ? ts(c.owners[0].since) : ''], ['affirmed', c => c.owners.length ? age(c.owners[0].affirmed_at) : '', 'n'], ['means', c => `<span class=dim title="${esc(c.description || '')}" style="display:inline-block;max-width:44ch;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;vertical-align:bottom">${esc(c.description || '')}</span>`]], cells, {empty: 'no aspects expected or claimed'});
      let tree = '';
      if (d.aff && d.aff.nodes.length > 1) {
        const line = n => `${'  '.repeat(n.depth)}${n.via ? '└ ' + n.via + ' ' : ''}`;
        tree = `<h2>built on this <small>${d.aff.nodes.length - 1} downstream · ${d.aff.parties.length} parties: ${d.aff.parties.map(p => `<span class="${p.contested ? 'bad' : ''}" title="${esc(p.roles.join('+'))} of ${esc(p.aspects.join(', '))} on ${p.records} record(s); nearest at depth ${p.nearest}">${esc(p.owner)}</span>`).join(' ')}</small></h2><pre class=tree>` +
          d.aff.nodes.slice(1, 40).map(n => `<span class=dim>${esc(line(n))}</span><a href="#target=${encodeURIComponent(n.target)}">${esc(n.target)}</a>  ${n.cells.map(c => `<span class=dim>${esc(c.aspect)}=</span><span class=${c.status}>${c.owners.map(h => esc(h.owner) + (h.source === 'TYPE' ? '~' : '')).join(',') || c.status}</span>`).join(' ')}`).join('\n') + '</pre>';
      }
      return `<h2>ownership <small>${cells.length} aspects${bad ? ` · <span class=warn>${bad} not cleanly owned</span>` : ''} · ~ = type default</small></h2>${tbl}
        <form class=row data-form=claim><input name=aspect placeholder="aspect" size=14 list=tlc-aspects><datalist id=tlc-aspects>${cells.map(c => `<option value="${esc(c.aspect)}">`).join('')}</datalist><input class=g name=owner placeholder="group you are in"><select name=role><option>OWNER</option><option>STAKEHOLDER</option></select><button>claim</button></form>${tree}`;
    }

    function tags(d) {
      const cur = table([['tag', r => `<span class=b>${esc(r.tag)}</span>`], ['since', r => ts(r.since)], ['age', r => age(r.since), 'n'], ['by', r => esc(r.by)], ['', r => `<button class=no data-act=untag data-id="${esc(r.tag)}">untag</button>`]], d.sum.tags, {empty: 'untagged'});
      const hist = d.hist.length ? table([['op', r => `<span class=${r.operation}>${r.operation}</span>`], ['tag', r => esc(r.tag)], ['effective', r => ts(r.effective_at)], ['recorded', r => ts(r.created_at)], ['by', r => esc(r.ldap || 'svc:' + r.service)]], d.hist) : '';
      return `<h2>tags <small>${d.sum.tags.length} now · last ${d.hist.length} ops below</small></h2>${cur}<form class=row data-form=tag><input class=g name=tag placeholder="tag name (created if new)"><input name=at placeholder="effective_at (ISO, optional)" size=26><button>tag</button></form>${hist}`;
    }

    function comment(c) {
      if (st.edit === c.comment_token) return `<div class=cm style="margin-left:${c.depth * 14}px"><form class=row data-form=edit data-id="${c.comment_token}"><input class=g name=content value="${esc(c.content)}"><button>save</button><button type=button data-act=cancel>cancel</button></form></div>`;
      const dead = !!c.deleted_at, own = c.author === user;
      return `<div class=cm style="margin-left:${c.depth * 14}px"><div class=hd><span class="${dead ? 'dim' : 'acc'}">${esc(c.author)}</span> ${ts(c.commented_at)} <span class=dim>${age(c.commented_at)}${c.edited_at ? ' · edited' : ''}</span> ${c.likes ? `<span class=warn>♥${c.likes}</span>` : ''}
        ${c.revision ? `<span class=dim title="written when the target was at revision ${esc(c.revision)}">@${esc(c.revision)}</span>` : ''} ${dead ? '' : `<a href=# data-act=${c.liked_by_me ? 'unlike' : 'like'} data-id="${c.comment_token}">${c.liked_by_me ? 'unlike' : 'like'}</a> <a href=# data-act=reply data-id="${c.comment_token}">reply</a>${own ? ` <a href=# data-act=edit data-id="${c.comment_token}">edit</a> <a href=# data-act=rm data-id="${c.comment_token}">rm</a>` : ''}`} <span class=dim>${esc(c.comment_token)}</span></div>
        <div class="tx ${dead ? 'dim' : ''}">${dead ? '[deleted]' : esc(c.content)}</div>
        ${st.reply === c.comment_token ? `<form class=row data-form=comment data-parent="${c.comment_token}"><input class=g name=content placeholder="reply to ${esc(c.author)}"><button>reply</button><button type=button data-act=cancel>cancel</button></form>` : ''}</div>`;
    }

    function comments(d) {
      const list = table([['thread', r => `<span class="${r.token === st.open ? 'info b' : ''}">${esc(r.title)}</span>`], ['on', r => (r.target && r.target.startsWith('AP-') ? `<span class=acc title="on the approval request">${esc(r.target)}</span>` : '<span class=dim>record</span>') + (r.revision ? `<span class=info>@${esc(r.revision)}</span>` : '')], ['by', r => esc(r.creator)], ['n', r => r.cached_comments, 'n'], ['last', r => ts(r.last_comment_at || r.created_at)], ['age', r => age(r.last_comment_at || r.created_at), 'n'], ['token', r => `<span class=dim>${esc(r.token)}</span>`]], d.sum.threads, {go: r => 'thread:' + r.token, cls: r => r.token === st.open ? 'sel' : '', empty: 'no threads'});
      const t = d.thread;
      const body = t ? `<div class=cm><div class=tx>${esc(t.thread.body || '')}</div>${t.comments.map(comment).join('')}<form class=row data-form=comment><input class=g name=content placeholder="comment on “${esc(t.thread.title)}”"><button>comment</button></form></div>` : '';
      return `<h2>comments <small>${d.sum.threads.length} threads · ${d.sum.threads.reduce((a, x) => a + x.cached_comments, 0)} comments</small></h2>${list}${body}<form class=row data-form=thread><select name=on title="what the thread is about"><option value="">on the record</option>${d.views.map(v => `<option value="${esc(v.approvable.token)}">on ${esc(v.approvable.token)}${v.approvable.revision ? '@' + esc(v.approvable.revision) : ''}</option>`).join('')}</select><input name=title placeholder="new thread title" size=28><input class=g name=body placeholder="body (optional)"><button>open thread</button></form>`;
    }

    function logs(d) {
      const lv = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'], min = lv.indexOf(st.level);
      const rows = d.sum.logs.filter(l => lv.indexOf(l.level) >= min);
      return `<h2>logs <small>${rows.length} shown</small><select data-change=level>${['', ...lv].map(l => `<option ${l === st.level ? 'selected' : ''} value="${l}">${l || 'all levels'}</option>`).join('')}</select></h2>` +
        table([['time', r => ts(r.timestamp)], ['age', r => age(r.timestamp), 'n'], ['lvl', r => `<span class=${r.level}>${r.level}</span>`], ['source', r => `<span class=dim>${esc(r.source)}</span>`], ['message', r => `<span class=${r.level}>${esc(r.message)}</span>`, 'w']], rows, {empty: 'no log entries'});
    }

    function render() {
      // Don't yank a form out from under someone typing; the next tick will catch up.
      const a = document.activeElement;
      if (a && root.contains(a) && ['INPUT', 'TEXTAREA', 'SELECT'].includes(a.tagName) && (a.value || a.tagName === 'SELECT')) return;
      const d = st.data;
      if (!st.target) { root.innerHTML = '<div class=dim>no target selected</div>'; return; }
      if (!d) { root.innerHTML = `<div class=dim>loading ${esc(st.target)}…</div><div class=err>${esc(st.err)}</div>`; return; }
      const e = d.sum.entity;
      const head = `<div class=row><span class="b info">${esc(d.sum.target)}</span>${e ? `<span>${esc(e.display_name || '')}</span><span class=dim>${esc(e.token)} v${e.version}</span>${e.url ? `<a href="${esc(e.url)}" target=_blank rel=noopener>source ↗</a>` : ''}${e.deleted_at ? '<span class=bad>destroyed</span>' : ''}` : '<span class=warn>not yet known to TLC (first write creates it)</span>'}</div>`;
      root.innerHTML = head + `<div class=err>${esc(st.err)}</div>` + (has('ownership') ? ownership(d) : '') + (has('approvals') ? approvals(d) : '') + (has('tags') ? tags(d) : '') + (has('comments') ? comments(d) : '') + (has('logs') ? logs(d) : '');
      opts.onrender && opts.onrender();
    }

    async function refresh() {
      try { await load(); st.err = st.err && st.sticky ? st.err : ''; st.sticky = false; root.classList.remove('stale'); }
      catch (e) { if (!st.sticky) st.err = String(e.message || e); root.classList.add('stale'); }
      render();
    }

    async function act(fn) {
      try { await fn(); st.err = ''; } catch (e) { st.err = String(e.message || e); st.sticky = true; }
      if (document.activeElement && root.contains(document.activeElement)) document.activeElement.blur();
      await refresh();
      opts.onchange && opts.onchange();
    }

    root.addEventListener('click', ev => {
      const go = ev.target.closest('tr[data-go]');
      const el = ev.target.closest('[data-act]');
      if (!el && go && go.dataset.go.startsWith('thread:')) { const t = go.dataset.go.slice(7); st.open = st.open === t ? null : t; st.reply = st.edit = null; return void refresh(); }
      if (!el) return;
      ev.preventDefault();
      const id = el.dataset.id, t = st.target;
      const m = {
        untag: () => act(() => api('POST', '/api/taggings/untag', {target: t, tag: id})),
        release: () => act(() => api('POST', '/api/ownership/release', {target: t, aspect: id, owner: el.dataset.owner})),
        approve: () => act(() => api('POST', `/api/approvables/${id}/approve`, {revision: el.dataset.rev || undefined})),
        unapprove: () => act(() => api('POST', `/api/approvables/${id}/unapprove`)),
        execute: () => act(() => api('POST', `/api/approvables/${id}/execute`, {revision: el.dataset.rev || undefined})),
        decline: () => act(() => api('POST', `/api/approvables/${id}/decline`, {reason: 'declined from the TLC panel'})),
        like: () => act(() => api('POST', `/api/comments/${id}/like`)),
        unlike: () => act(() => api('POST', `/api/comments/${id}/unlike`)),
        rm: () => act(() => api('DELETE', `/api/comments/${id}`)),
        reply: () => { st.reply = id; st.edit = null; render(); root.querySelector('form[data-parent] input')?.focus(); },
        edit: () => { st.edit = id; st.reply = null; render(); },
        cancel: () => { st.reply = st.edit = null; document.activeElement?.blur(); render(); },
      }[el.dataset.act];
      m && m();
    });
    root.addEventListener('submit', ev => {
      ev.preventDefault();
      const f = ev.target, v = Object.fromEntries(new FormData(f)), t = st.target;
      const m = {
        tag: () => v.tag && api('POST', '/api/taggings/tag', {target: t, tag: v.tag, effective_at: v.at || undefined, create_tag: true}),
        request: () => api('POST', '/api/approvables', {target: t, rule: v.rule, context: v.context || undefined, mode: v.ratify ? 'RATIFY' : undefined}),
        claim: () => v.aspect && v.owner && api('POST', '/api/ownership/claim', {target: t, aspect: v.aspect, owner: v.owner, role: v.role}),
        thread: () => v.title && api('POST', '/api/threads', {target: v.on || t, title: v.title, body: v.body || undefined}).then(r => { st.open = r.token; }),
        comment: () => v.content && api('POST', '/api/comments', {thread: st.open, parent_comment: f.dataset.parent, content: v.content}).then(() => { st.reply = null; }),
        edit: () => api('PATCH', `/api/comments/${f.dataset.id}`, {content: v.content}).then(() => { st.edit = null; }),
      }[f.dataset.form];
      if (m) { f.querySelectorAll('input').forEach(i => { i.blur(); }); act(async () => { await m(); }); }
    });
    root.addEventListener('change', ev => { if (ev.target.dataset.change === 'level') { st.level = ev.target.value; ev.target.blur(); render(); } });

    return {refresh, render, get target() { return st.target; }, setTarget(t) { if (t !== st.target) { st.target = t; st.data = null; st.open = st.reply = st.edit = null; st.err = ''; } return refresh(); }, openThread(t) { st.open = t; }};
  }

  // Self-rescheduling poll: one tick in flight at most, parked while hidden, stopped on unload.
  function poll(fn, ms) {
    let live = true, inflight = false, timer = null;
    async function tick() {
      clearTimeout(timer);
      if (!live || inflight || document.hidden) return;
      inflight = true;
      try { await fn(); } catch (e) { console.warn('tick failed', e); }
      finally { inflight = false; if (live && !document.hidden) timer = setTimeout(tick, ms); }
    }
    const vis = () => { if (!document.hidden) tick(); };
    document.addEventListener('visibilitychange', vis);
    addEventListener('pagehide', () => { live = false; clearTimeout(timer); document.removeEventListener('visibilitychange', vis); });
    tick();
    return tick;
  }

  return {api, q, esc, ts, age, table, panel, poll, qs, get user() { return user; }, setUser};
})();
