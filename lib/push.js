// Push notifications to the installed app. Each ping is a message on a Vercel Queue delayed
// to the exact minute it's due; when it arrives (api/push-fire.js) we re-check that it still
// matters (the step isn't done, the habit is still open) and only then push. So nothing
// needs cancelling: a stale message just finds nothing to say. No per-minute polling, which
// keeps storage reads low.
import webpush from 'web-push';
import { QueueClient } from '@vercel/queue';
import { fcmAccount, fcmSendAll } from './fcm.js';
import { planOccurrences } from './mood.js';
import { todayView } from './today.js';
import { weekCard } from './metrics.js';
import { localParts, localToEpoch, addDays, routineStatus, habitStatus, scheduledOn, effectiveTime, bedtimeStatus, nightRoutine, rankTasks, placeOf } from './logic.js';

export const TOPIC = 'cue-push';
// Unpinned: a ping is delivered to whatever is in production when it comes due, not the
// deployment that queued it, so a fix reaches pings queued at this morning's sweep. Safe
// because delivery re-checks everything from the key alone.
const { send } = new QueueClient({ deploymentId: null });
const vapidReady = () => !!(process.env.VAPID_PUBLIC_KEY && process.env.VAPID_PRIVATE_KEY);
// Web push (VAPID) for the installed web app, FCM for the native Android app; either is enough.
export const pushReady = () => vapidReady() || !!fcmAccount();
const prefs = s => ({ week: true, now: true, idle: true, idle_min: 60, gates: true, times: false, dont_miss_twice: true, nudge_time: '20:30', bedtime: true, mood: false, ...(s.push || {}) });
export const nowOn = s => prefs(s).now;
export const NOW_TAG = 'cue-now';

// The Now notification: one notification (one tag, replaced in place) that always shows the
// single next thing, with Done and Not now buttons that swap in the one after it. Order: the
// routine that's running (core steps only when it has any; a ready non-core step fills a
// gate's wait), then habits whose time has come (timed ones first), then the top task.
// Nothing before wake; from wind-down only the night routine, and nothing once it's done.
// "Not now" only hides an item from the notification for the rest of the day.
export function nowItem(state, now = Date.now()) {
  const s = state.settings;
  const lp = localParts(now, s.tz), today = lp.date;
  const bed = bedtimeStatus(state, now);
  if (!bed?.phase && lp.time < (s.wake || '07:00')) return null;
  const hidden = new Set(state.push?.now_skip?.date === today ? state.push.now_skip.keys : []);
  // A running focus block holds the notification until it's stopped.
  const sess = state.focus?.sessions?.find(x => !x.ended);
  if (sess) {
    const end = Date.parse(sess.started) + (sess.paused_ms || 0) + sess.planned_min * 60000;
    const over = !sess.paused_at && now >= end;
    const len = sess.planned_min * 60000, gone = Math.min(len, (sess.paused_at ? Date.parse(sess.paused_at) : now) - Date.parse(sess.started) - (sess.paused_ms || 0));
    return { key: `focus:${sess.id}`, title: sess.title, ctx: sess.paused_at ? 'Focus · paused' : over ? 'Focus · time\'s up' : `Focus · until ${localParts(end, s.tz).time}`,
      body: over ? 'Done if it\'s finished' : '', icon: `/ring.png?v=2&d=${Math.max(0, Math.round(gone / 60000))}&n=${sess.planned_min}`,
      prog: [Math.max(0, Math.round(gone / 60000)), sess.planned_min], ...(!sess.paused_at && !over ? { ends: end } : {}),
      check: { kind: 'focus', id: sess.id }, noskip: true };
  }
  const night = bed?.phase ? nightRoutine(state) : null;

  const routines = state.routines.filter(r => !r.archived).map(r => {
    const day = r === night ? bed.evening : today;
    const st = routineStatus(r, day, now, s.tz);
    const time = effectiveTime(r, day) || (r === night ? bed.wind_down_at : null);
    return { r, st, day, time };
  }).filter(x => x.st.scheduled && !x.st.done && !x.st.skipped && (x.r === night || (x.time && x.time <= lp.time)))
    .filter(x => !bed?.phase || x.r === night)
    .sort((a, b) => a.time.localeCompare(b.time));
  for (const { r, st, day } of routines) {
    // Not now on any step means not this routine right now: its steps run in order, so the
    // next one (clean the pan) makes no sense once the first (cook dinner) was put off.
    if ([...hidden].some(k => k.startsWith(`step:${r.id}:`))) continue;
    const open = st.steps.filter(x => !x.done && !x.skipped);
    const hasCore = r.steps.some(x => x.core);
    const want = hasCore ? open.filter(x => x.core) : open;
    if (!want.length) continue;
    const step = want.find(x => !x.ready_ts) || open.find(x => !x.core && !x.ready_ts);
    if (!step) continue; // everything left is waiting on a gate; its ping brings it back
    const pool = hasCore ? st.steps.filter(x => x.core) : st.steps;
    const filler = hasCore && !step.core;
    const done = pool.filter(x => x.done || x.skipped).length;
    // A step with a metric is scored in the app (tap opens the 0-10 picker); on a trial's off
    // day the whole step is the score.
    if (step.metric) {
      return {
        key: `step:${r.id}:${step.id}`, title: step.trial_off ? `Score ${step.metric.name}` : step.title, ctx: r.name,
        body: step.trial_off ? `Off day for ${step.title}: don't apply` : `Then score ${step.metric.name}`,
        icon: `/ring.png?v=2&d=${done}&n=${pool.length}`, prog: [done, pool.length], url: `/?score=${r.id}:${step.id}`,
      };
    }
    return {
      key: `step:${r.id}:${step.id}`, title: step.title, ctx: `${r.name} · ${filler ? 'while you wait' : `${done + 1}/${pool.length}`}`,
      body: [step.est_min ? `${step.est_min} min` : null,
        // No step notes: a routine repeats daily, so its notes are reference, not news.
        filler ? `${want[0].title} at ${want[0].wait_until}` : null].filter(Boolean).join('\n'),
      icon: `/ring.png?v=2&d=${done}&n=${pool.length}`, prog: [done, pool.length], place: placeOf(step.context),
      check: { kind: 'routine_step', id: r.id, step_id: step.id, date: day },
    };
  }
  if (bed?.phase) return null;

  const habits = state.habits.filter(h => !h.archived && habitStatus(h, today).due_today && !hidden.has(`habit:${h.id}`))
    .map(h => ({ h, time: effectiveTime(h, today) }))
    .filter(x => !x.time || x.time === 'anytime' || x.time <= lp.time)
    .sort((a, b) => ((a.time && a.time !== 'anytime') ? a.time : '99').localeCompare((b.time && b.time !== 'anytime') ? b.time : '99'));
  if (habits.length) {
    const { h } = habits[0];
    const due = state.habits.filter(x => !x.archived && scheduledOn(x, today) && !x.log?.[today]?.skipped);
    const done = due.filter(x => x.log?.[today]?.done).length;
    return { key: `habit:${h.id}`, title: h.name, ctx: `Habit · ${done + 1}/${due.length}`, body: `After I ${h.cue || '...'}, I will ${h.behavior || h.name}`,
      icon: `/ring.png?v=2&d=${done}&n=${due.length}`, prog: [done, due.length], check: { kind: 'habit', id: h.id, date: today } };
  }

  // Tasks: a hard deadline due today breaks in first; then today's list; then bonus.
  const { scored } = rankTasks(state, now);
  const day = todayView(state, now);
  const visible = scored.filter(x => !hidden.has(`task:${x.t.id}`));
  const urgent = visible.find(x => x.t.hard && x.t.due && x.t.due.slice(0, 10) <= today && !day.show.includes(x.t.id));
  const listed = visible.find(x => day.show.includes(x.t.id));
  const top = urgent || listed || visible[0];
  if (!top) return null;
  const t = top.t;
  const ctx = urgent && top === urgent ? 'Due today' : top === listed ? (day.mode === 'low' ? 'Low day · just this one' : `Today · ${day.done + 1}/${day.total}`) : `Bonus · ${t.project || 'Task'}`;
  // Anything over 10 minutes gets Start 25 min (a focus block) in place of Done.
  const long = (t.est_min || s.default_est_min || 15) > 10 && state.tasks.some(x => x.id === t.id);
  return { key: `task:${t.id}`, title: t.title, ctx, place: placeOf(t.context), ...(long ? { start: { task_id: t.id } } : {}),
    body: [[`~${t.est_min || s.default_est_min || 15} min`, t.due ? (t.due < today ? 'overdue' : `due ${t.due.slice(5, 10)}`) : null].filter(Boolean).join(' · '), brief(t.notes)].filter(Boolean).join('\n'),
    icon: `/ring.png?v=2&d=${Math.min(day.done, day.total)}&n=${Math.max(1, day.total)}`, prog: [Math.min(day.done, day.total), Math.max(1, day.total)], check: { kind: 'task', id: t.id } };
}

// The first sentence or so of a note, for the notification's expanded view.
function brief(notes, max = 110) {
  const line = String(notes || '').split('\n').find(x => x.trim())?.trim() || '';
  if (line.length <= max) return line;
  return line.slice(0, line.lastIndexOf(' ', max)).replace(/[,;:.]$/, '') + '…';
}

// Idle nudge: during personal hours, an hour with nothing checked off, logged or focused on
// re-buzzes the Now item as "stand up first". Every activity queues a check an hour out
// (idle:<date>:<ms>:<n>), so nothing polls; a check that finds newer activity does nothing,
// and after IDLE_MAX ignored nudges in a row it stops until the next activity.
export const IDLE_MAX = 2;
export function lastActive(state) {
  return activityTimes(state).reduce((a, x) => Math.max(a, x), 0);
}
// Every moment he did something (epoch ms). mood: false leaves out mood logs, for asking
// whether he got something done rather than just tapped a rating.
export function activityTimes(state, { mood = true } = {}) {
  const out = [];
  const at = v => { const x = Date.parse(v || ''); if (x) out.push(x); };
  for (const x of state.tasks) for (const h of x.history || []) at(h.done);
  for (const h of state.habits) for (const e of Object.values(h.log || {})) at(e?.at);
  for (const r of state.routines) for (const e of Object.values(r.log || {})) for (const v of Object.values(e?.at || {})) at(v);
  for (const f of state.focus?.sessions || []) { at(f.started); at(f.ended); }
  if (mood) for (const e of state.mood?.entries || []) at(e.at);
  for (const w of state.lift?.sessions || []) { at(w.started); for (const x of w.sets || []) at(x.at); }
  for (const c of state.cook?.cooks || []) { at(c.started); at(c.finished); }
  return out;
}

// The idle nudge as his own micro-randomized trial: when one is due, a coin flip sends it or
// holds it, and both arms are logged (meta.idle_trial). Prompt effects tend to fade within
// weeks (HeartSteps: gone by day 28), so whether this one still works for him is measured, not
// assumed: the share of checks followed by something done within the hour, sent vs held.
export const IDLE_TITLES = ['Stand up first', 'Up for the next one?', 'One thing, then sit back down'];
export function idleTrial(state, now = Date.now()) {
  const acts = activityTimes(state, { mood: false });
  const arm = name => {
    const xs = (state.meta?.idle_trial || []).filter(x => x.arm === name && Date.parse(x.at) + 3600e3 <= now);
    const acted = xs.filter(x => { const t = Date.parse(x.at); return acts.some(a => a > t && a <= t + 3600e3); }).length;
    return { n: xs.length, acted, rate: xs.length ? +(acted / xs.length).toFixed(2) : null };
  };
  const sent = arm('sent'), held = arm('held');
  return { sent, held, ...(sent.rate != null && held.rate != null ? { difference: +(sent.rate - held.rate).toFixed(2) } : {}),
    ...(sent.n + held.n < 20 ? { note: 'Under 20 checks: too few to read yet.' } : {}) };
}

// When a running focus block ends (epoch ms), or null: a buzz waits for it like a calendar event.
export function focusEndsAt(state, now = Date.now()) {
  const f = (state.focus?.sessions || []).find(x => !x.ended && !x.paused_at);
  if (!f) return null;
  const end = Date.parse(f.started) + f.planned_min * 60000 + (f.paused_ms || 0);
  return end > now ? end : null;
}
function inPersonalHours(s, lp) {
  const w = (lp.dow === 0 || lp.dow === 6 ? s.personal_hours?.weekend : s.personal_hours?.weekday) || { start: s.wake || '07:00', end: '22:00' };
  return lp.time >= w.start && lp.time < w.end;
}
export function idleCheck(state, now, n) {
  const s = state.settings, p = prefs(s);
  const min = p.idle_min * 60000;
  if (!p.now || !p.idle || now - lastActive(state) < min - 60000) return null;
  const lp = localParts(now, s.tz);
  if (!inPersonalHours(s, lp) || bedtimeStatus(state, now)?.phase) return null;
  const c = nowContent(state, now, { loud: true });
  if (!c || c.now_key.startsWith('focus:')) return null;
  // Something he can do from the phone in hand gets the plain Now title: standing up first
  // only makes sense when the next thing needs him somewhere.
  const phone = nowItem(state, now)?.place === 'phone';
  // The wording rotates (by day and nudge number) so it doesn't become wallpaper.
  const title = IDLE_TITLES[(Math.floor(now / 864e5) + n) % IDLE_TITLES.length];
  return { ...c, ...(phone ? {} : { title, body: [c.title, c.body].filter(Boolean).join('\n') }), ...(n < IDLE_MAX ? { followup: { key: `idle:${lp.date}:${now}:${n + 1}`, at: now + min } } : {}) };
}
export function idlePing(state, now = Date.now()) {
  const p = prefs(state.settings);
  if (!p.now || !p.idle) return null;
  return { key: `idle:${localParts(now, state.settings.tz).date}:${now}:1`, at: now + p.idle_min * 60000 };
}

// The push payload for the Now item. loud: buzz (a scheduled moment); otherwise it replaces
// the current one quietly (after a Done or a check-off in the app).
export function nowContent(state, now = Date.now(), { loud = false } = {}) {
  const it = nowItem(state, now);
  if (!it) return null;
  // The action is the title (titles and step names are kept to about 24 characters so it
  // fits beside the site name); the context ("Morning routine · 4/5") leads the body.
  const body = [it.ctx, it.body].filter(Boolean).join('\n');
  // ctx, prog and ends are for the native app, which lays them out itself (a progress bar, a
  // countdown); the web notification only uses title and body.
  return { title: it.title || it.ctx || 'Cue', body, ctx: it.ctx || '', icon: it.icon || '/icons/icon-192.png', ...(it.prog ? { prog: it.prog } : {}), ...(it.ends ? { ends: it.ends } : {}), ...(it.url ? { url: it.url } : {}), tag: NOW_TAG, ...(it.start ? { start: it.start } : { check: it.check }), skip: it.noskip ? null : it.key, now_key: it.key, renotify: loud, silent: !loud };
}
export const MOOD_EVERY_H = 2;

// Queue a ping for epoch ms `at`. The key dedupes repeat schedules of the same ping.
export async function schedulePing(key, at, data = {}) {
  if (!pushReady()) return false;
  const delay = Math.max(0, Math.round((at - Date.now()) / 1000));
  if (delay > 604800) return false;
  await send(TOPIC, { key, at, ...data }, {
    idempotencyKey: `${key}@${at}`,
    delaySeconds: delay,
    retentionSeconds: Math.min(604800, Math.max(86400, delay + 3600)),
  });
  return true;
}

// Everything worth pinging today that can be scheduled ahead: timed routines and habits (if
// on), the evening don't-miss-twice check, tonight's wind-down and bedtime (if set), and
// tomorrow's 6am wake-up that keeps the chain going even on a day nobody opens Cue.
export function dayPlan(state, now = Date.now()) {
  const s = state.settings, p = prefs(s);
  const today = localParts(now, s.tz).date;
  const at = hm => localToEpoch(`${today}T${hm}`, s.tz);
  const out = [];
  // Now: buzz at wake and whenever a routine or habit's time comes, showing the next thing.
  if (p.now) {
    const times = new Set([s.wake || '07:00']), quiet = new Set();
    for (const x of [...state.routines, ...state.habits]) {
      const t = !x.archived && scheduledOn(x, today) && effectiveTime(x, today);
      if (!t || t === 'anytime') continue;
      // An automatic habit's time only refreshes the notification quietly (see habitAutomatic).
      if (state.habits.includes(x) && habitStatus(x, today).automatic) quiet.add(t); else times.add(t);
    }
    for (const t of times) if (at(t) > now) out.push({ key: `now:${today}:${t.replace(':', '')}`, at: at(t) });
    for (const t of quiet) if (!times.has(t) && at(t) > now) out.push({ key: `now:${today}:r${at(t)}`, at: at(t) });
  }
  if (p.times && !p.now) {
    for (const x of [...state.routines, ...state.habits]) {
      const t = !x.archived && scheduledOn(x, today) && effectiveTime(x, today);
      if (t && at(t) > now) out.push({ key: `time:${x.id}:${today}:${t}`, at: at(t) });
    }
  }
  if (p.dont_miss_twice && !p.now && at(p.nudge_time) > now) out.push({ key: `dmt:${today}`, at: at(p.nudge_time) });
  // Tonight is the evening of `today`, so plan from noon even when this runs at 6am.
  const bed = p.bedtime && bedtimeStatus(state, localToEpoch(`${today}T12:00`, s.tz));
  if (bed) {
    if (bed.wind_at > now) out.push({ key: `bed:${today}:wind`, at: bed.wind_at });
    if (bed.bed_at > now) out.push({ key: `bed:${today}:now`, at: bed.bed_at });
  }
  // Mood check-ins: every 2 hours from 2h after wake until an hour before bed (23:00 if unset).
  if (p.mood) {
    const first = at(s.wake || '07:00') + MOOD_EVERY_H * 3600e3;
    const bedAt = (bed || bedtimeStatus(state, localToEpoch(`${today}T12:00`, s.tz)))?.bed_at;
    const last = (bedAt || at('23:00')) - 3600e3;
    for (let t = first; t <= last; t += MOOD_EVERY_H * 3600e3) {
      if (t > now) out.push({ key: `mood:${today}:${localParts(t, s.tz).time.replace(':', '')}`, at: t });
    }
  }
  // Planned activities (Mood): today's task plans and habit plans, at their time.
  for (const pl of state.mood?.plans || []) {
    const o = planOccurrences(state, pl, { from: today, today, tz: s.tz })[0];
    if (o?.status === 'upcoming' && at(pl.time) > now) out.push({ key: `plan:${pl.id}:${today}`, at: at(pl.time) });
  }
  // The first idle check of the evening (or weekend morning): an hour into personal hours.
  if (p.now && p.idle) {
    const w = ([0, 6].includes(localParts(at('12:00'), s.tz).dow) ? s.personal_hours?.weekend : s.personal_hours?.weekday);
    const t = w?.start && at(w.start) + p.idle_min * 60000;
    if (t && t > now) out.push({ key: `idle:${today}:${t}:1`, at: t });
  }
  // Sunday 10:00: "Your week".
  if (p.week && localParts(at('12:00'), s.tz).dow === 0 && at('10:00') > now) out.push({ key: `week:${today}`, at: at('10:00') });
  out.push({ key: `day:${addDays(today, 1)}`, at: localToEpoch(`${addDays(today, 1)}T06:00`, s.tz) });
  return out;
}

// What a ping should say right now, or null if it no longer applies. `check` (a check_off
// item) gives the notification a Done button that ticks it without opening the app.
export function pingContent(state, key, now = Date.now(), msg = null) {
  const s = state.settings, p = prefs(s);
  const today = localParts(now, s.tz).date;
  const [kind, id, date, ...rest] = key.split(':');
  const extra = rest.join(':'); // a step id, or an HH:MM time (which has its own colon)
  // now:<date>:<HHMM> buzzes at a set time; now:<date>:r<ms> quietly refreshes it after a
  // check-off in the app, unless it would show what's already showing.
  if (kind === 'now') {
    if (!p.now || id !== today) return null;
    const loud = !date.startsWith('r');
    const c = nowContent(state, now, { loud });
    if (!c || (!loud && state.push?.now_last === c.now_key)) return null;
    return c;
  }
  // With Now on, a gate opening or a snooze ending just brings the Now notification up to date.
  if (p.now && (kind === 'gate' || kind === 'snooze') && date === today) return nowContent(state, now, { loud: true });
  if (kind === 'gate' && p.gates) {
    const r = state.routines.find(x => x.id === id);
    if (!r || date !== today) return null;
    const st = routineStatus(r, today, now, s.tz);
    const step = st.steps.find(x => x.id === extra);
    if (!step || step.done || step.skipped || st.done || st.skipped) return null;
    return { title: `Ready: ${step.title}`, body: `${r.name} · the wait is over`, tag: key, check: { kind: 'routine_step', id: r.id, step_id: step.id, date: today } };
  }
  // time: a routine or habit's set time (if times are on and Now is off: Now buzzes then
  // instead, and a time ping queued before Now was turned on would double it); snooze: the
  // time he moved it to.
  if ((kind === 'time' && p.times && !p.now || kind === 'snooze') && date === today) {
    const x = state.routines.find(y => y.id === id) || state.habits.find(y => y.id === id);
    if (kind === 'time' && x?.snooze?.date === today) return null; // the snooze ping covers it
    const r = state.routines.find(x => x.id === id);
    if (r) {
      const st = routineStatus(r, today, now, s.tz);
      if (st.done || st.skipped || effectiveTime(r, today) !== extra) return null;
      return { title: r.name, body: st.next_step ? `Next: ${st.next_step.title}` : 'Time to start', tag: key };
    }
    const h = state.habits.find(x => x.id === id);
    if (!h || !habitStatus(h, today).due_today || effectiveTime(h, today) !== extra) return null;
    return { title: h.name, body: `After I ${h.cue || '...'}, I will ${h.behavior || h.name}`, tag: key, check: { kind: 'habit', id: h.id, date: today } };
  }
  if (kind === 'dmt' && p.dont_miss_twice && !p.now && id === today) {
    const flagged = state.habits.filter(h => !h.archived && habitStatus(h, today).dont_miss_twice);
    if (!flagged.length) return null;
    return { title: "Don't miss twice", body: `${flagged.map(h => h.name).join(', ')}: missed yesterday. The small version tonight keeps it alive.`, tag: key };
  }
  // bed:<evening>:wind|now: start winding down (unless the night routine is already done), then bed.
  if (kind === 'bed' && p.bedtime) {
    const b = bedtimeStatus(state, now);
    if (!b || b.evening !== id) return null;
    const r = nightRoutine(state), st = r && routineStatus(r, id, now, s.tz);
    if (date === 'wind') {
      if (b.routine_done) return null;
      return { title: 'Wind down', body: `Bed at ${b.time}.` + (st?.next_step ? ` ${r.name}: ${st.next_step.title}` : ''), tag: 'cue-bed' };
    }
    if (date === 'now') {
      const left = st && !b.routine_done ? st.steps.filter(x => !x.done && !x.skipped).length : 0;
      return { title: 'Bedtime', body: left ? `${left} night routine step${left > 1 ? 's' : ''} left. Core only, then lights out.` : 'Phone down, lights out.', tag: 'cue-bed' };
    }
    return null;
  }
  // rest:<session>:<sets logged>: rest after a set is over, unless he's already logged another.
  if (kind === 'rest') {
    const sess = state.lift?.sessions?.find(x => x.id === id);
    if (!sess || sess.finished || String(sess.sets.length) !== date) return null;
    return { title: 'Rest over', body: `Next: ${msg?.next || 'your next set'}`, tag: 'cue-rest' };
  }
  // focus:<session>:<end ms>: the block ran out (still running, not paused or extended).
  if (kind === 'focus') {
    const sess = state.focus?.sessions?.find(x => x.id === id);
    if (!sess || sess.ended || sess.paused_at) return null;
    const end = Date.parse(sess.started) + (sess.paused_ms || 0) + sess.planned_min * 60000;
    if (Math.abs(end - Number(date)) > 2000) return null;
    return { title: 'Focus block done', body: `${sess.title} · log how it went`, tag: 'cue-focus' };
  }
  if (kind === 'break') {
    const b = state.focus?.brk;
    if (!b || String(b.until) !== date || state.focus.sessions.some(x => !x.ended)) return null;
    const sess = state.focus.sessions.find(x => x.id === id);
    return { title: 'Break over', body: sess ? `Back to: ${sess.title}` : 'Back to it', tag: 'cue-focus' };
  }
  // cook:<cook>:<end ms>: a kitchen timer ran out (and wasn't cancelled or the cook finished).
  if (kind === 'cook') {
    const c = state.cook?.cooks?.find(x => x.id === id);
    const t = c && !c.finished && c.timers.find(x => String(Date.parse(x.ends)) === date);
    if (!t) return null;
    return { title: 'Timer done', body: t.label, tag: `cue-cook-${date}` };
  }
  // mood:<date>:<HHMM>: a check-in, unless he logged one in the last hour.
  if (kind === 'mood' && p.mood && id === today) {
    if ((state.mood?.entries || []).some(e => now - Date.parse(e.at) < 3600e3)) return null;
    return { title: 'What are you doing?', body: 'Mood 0-10. Tap to log it.', tag: 'cue-mood', url: '/?mood=1' };
  }
  // plan:<plan>:<date>: a planned activity is due, unless it's done, rated or no longer planned.
  if (kind === 'plan' && id && date === today) {
    const pl = state.mood?.plans?.find(x => x.id === id);
    const o = pl && planOccurrences(state, pl, { from: today, today, tz: s.tz })[0];
    if (!o || o.status !== 'upcoming' || o.rated) return null;
    return { title: `Planned: ${pl.activity}`, body: `You predicted ${pl.predicted.mood}/10. Tap to rate it when you're done.`, tag: key, url: `/mood?plan=${pl.id}` };
  }
  if (kind === 'idle' && id === today) return idleCheck(state, now, Number(extra) || 1);
  if (kind === 'week' && p.week && id === today) {
    const w = weekCard(state, now);
    if (!w) return null;
    return { title: 'Your week', body: `${w.done_count} thing${w.done_count === 1 ? '' : 's'} done this week. Tap for the rundown.`, tag: 'cue-week', url: '/?week=1' };
  }
  if (kind === 'test') return { title: 'Cue notifications are on', body: 'Wait timers and nudges will show up here.', tag: 'test' };
  return null;
}

// Push to every subscribed device (web push subscriptions and native app tokens); returns
// the ones that are gone so the caller can drop them (dropGone), and why any other send failed
// (a bad VAPID key or payload shows up here, not as silence).
export async function pushAll(state, msg) {
  const gone = [], errors = [];
  let sent = 0;
  if (vapidReady()) {
    webpush.setVapidDetails(process.env.VAPID_SUBJECT || 'mailto:cue@example.com', process.env.VAPID_PUBLIC_KEY, process.env.VAPID_PRIVATE_KEY);
    for (const sub of state.push?.subs || []) {
      try { await webpush.sendNotification(sub, JSON.stringify({ url: '/', ...msg }), { TTL: 3600, urgency: 'high' }); sent++; }
      catch (e) {
        if (e.statusCode === 404 || e.statusCode === 410) gone.push(sub.endpoint);
        else errors.push(`${e.statusCode || 'error'} ${String(e.body || e.message || '').slice(0, 160)}`.trim());
      }
    }
  }
  const tokens = (state.push?.fcm || []).map(x => x.token);
  if (tokens.length) {
    const r = await fcmSendAll(fcmAccount(), tokens, { url: '/', ...msg });
    sent += r.sent; gone.push(...r.gone); errors.push(...r.errors);
  }
  return { sent, gone, errors };
}

// Drop dead web push subscriptions and app tokens (inside a mutate).
export function dropGone(s, gone) {
  if (!gone?.length || !s.push) return;
  s.push.subs = (s.push.subs || []).filter(x => !gone.includes(x.endpoint));
  if (s.push.fcm) s.push.fcm = s.push.fcm.filter(x => !gone.includes(x.token));
}
