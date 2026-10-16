import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const checker = fileURLToPath(new URL('../../scripts/check-js-boundary.mjs', import.meta.url));
const entryPath = 'backend/scripts/translation_split_review_fixture.cljs';
const namedAdapter = 'backend/src/cljs/knoxx/backend/extern/node_cli.cljs';
const caller = 'backend/src/cljs/knoxx/backend/infra/boundary_probe.cljs';
const clean = `(ns translation-split-review-fixture
  (:require [knoxx.backend.extern.node-cli :as cli]))
(defn main! [] (cli/write-result! {:action "read"}))
`;

/** Run the actual checked-in checker in a disposable layout, without a DB or app. */
function layout(t, entry = clean, allow = []) {
  const root = mkdtempSync(join(tmpdir(), 'knoxx-fixture-boundary-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const put = (path, content) => {
    const file = join(root, path);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, content);
    return file;
  };
  mkdirSync(join(root, 'backend/src/cljs/knoxx/backend/extern'), { recursive: true });
  mkdirSync(join(root, 'backend/scripts'), { recursive: true });
  copyFileSync(checker, join(root, 'backend/scripts/check-js-boundary.mjs'));
  put('backend/config/js-boundary-allowlist.json', JSON.stringify({ allowedGenericExternImports: allow }));
  if (entry !== null) put(entryPath, entry);
  return {
    root, put,
    run(mode = '--check') {
      const result = spawnSync(process.execPath, [join(root, 'backend/scripts/check-js-boundary.mjs'), mode], {
        cwd: root, encoding: 'utf8', timeout: 10_000, maxBuffer: 1_048_576,
      });
      assert.ifError(result.error);
      assert.equal(result.signal, null, result.stderr);
      return { ...result, output: result.stdout + result.stderr };
    },
  };
}

test('the fixture entry is checked even when the source inventory has no generic imports', t => {
  const result = layout(t, '(ns translation-split-review-fixture)\n(defn main! [] (.-env js/process))\n').run();
  assert.notEqual(result.status, 0, 'The existing fixture script must not bypass the registered checker');
  assert.ok(result.output.includes(entryPath), result.output);
  assert.match(result.output, /native env\/process/);
});

const forbidden = [
  ['native env/process', '(defn probe [] js/process)'],
  ['native env/process', '(defn probe [] (.-env js/process))'],
  ['native env/process', '(defn probe [] (set! (.-exitCode js/process) 1))'],
  ['native Date', '(defn probe [] (js/Date.))'],
  ['native JSON', '(defn probe [value] (.stringify js/JSON value))'],
  ['native console', '(defn probe [] (js/console.error "unexpected"))'],
  ['native conversion', '(defn probe [value] (js->clj value))'],
  ['native conversion', '(defn probe [value] (clj->js value))'],
  ['native array access', '(defn probe [value] (aget value "status"))'],
  ['native array access', '(defn probe [value] (aset value "status" 1))'],
  ['native literal', '(def value #js {:private true})'],
  ['native construction', '(defn probe [uri] (MongoClient. uri))'],
  ['native construction', '(defn probe [constructor] (new constructor))'],
  ['native property/method', '(defn probe [error] (.-message error))'],
  ['native property/method', '(defn probe [error] (.-stack error))'],
  ['native property/method', '(defn probe [db] (.collection db "segments"))'],
  ['native property/method', '(defn probe [client] (. client connect))'],
  ['native module import', '(ns probe (:require ["mongodb" :refer [MongoClient]]))'],
  ['native module import', '(ns probe (:require ["node:process" :as process]))'],
  ['generic extern import', '(ns probe (:require [knoxx.backend.extern.js :as xjs]))'],
  ['generic extern import', '(ns probe (:require [knoxx.backend.extern.json :as xjson]))'],
];

for (const [category, form] of forbidden) {
  test(`strict fixture entry refuses ${category}: ${form}`, t => {
    const result = layout(t, `${form}\n`).run();
    assert.notEqual(result.status, 0, result.output);
    assert.ok(result.output.includes(entryPath), result.output);
    assert.ok(result.output.includes(category), result.output);
  });
}

test('the generic import allowlist cannot exempt the strict fixture entry', t => {
  const result = layout(t, '(ns probe (:require [knoxx.backend.extern.js :as xjs]))\n', [entryPath]).run();
  assert.notEqual(result.status, 0, result.output);
  assert.ok(result.output.includes(entryPath), result.output);
});

test('missing fixture entry fails instead of turning a source inventory into a pass', t => {
  const result = layout(t, null).run();
  assert.notEqual(result.status, 0, result.output);
  assert.ok(result.output.includes(entryPath), result.output);
  assert.match(result.output, /unreadable fixture entry/);
});

test('unreadable fixture entry fails with its exact path', t => {
  const fixture = layout(t);
  chmodSync(join(fixture.root, entryPath), 0);
  const result = fixture.run();
  assert.notEqual(result.status, 0, result.output);
  assert.ok(result.output.includes(entryPath), result.output);
  assert.match(result.output, /unreadable fixture entry/);
});

test('unknown or malformed fixture scans refuse rather than claim the entry clean', t => {
  for (const source of ['(ns unterminated', '(def text "unterminated)', '(def data [1 2))']) {
    const result = layout(t, source).run();
    assert.notEqual(result.status, 0, result.output);
    assert.ok(result.output.includes(entryPath), result.output);
    assert.match(result.output, /unknown fixture scan/);
  }
});

test('pure orchestration through named adapters and host words in comments or strings pass', t => {
  const result = layout(t, `${clean}
; js/process (.-message error) (MongoClient. uri) #js {} aget
(def explanation "js/Date js/JSON clj->js js->clj \\"quoted\\" #js (.close db)")
`).run();
  assert.equal(result.status, 0, result.output);
  assert.match(result.output, /JS boundary check passed/);
});

test('the same real host interop stays legal inside its named extern adapter', t => {
  const fixture = layout(t);
  fixture.put(namedAdapter, '(ns knoxx.backend.extern.node-cli)\n(defn args [] (js->clj (.-argv js/process)))\n');
  const result = fixture.run();
  assert.equal(result.status, 0, result.output);
});

test('ordinary generic-extern source imports still require their existing allowlist', t => {
  const source = '(ns knoxx.backend.infra.boundary-probe (:require [knoxx.backend.extern.js :as xjs]))\n';
  const denied = layout(t);
  denied.put(caller, source);
  const rejected = denied.run();
  assert.notEqual(rejected.status, 0, rejected.output);
  assert.ok(rejected.output.includes(caller), rejected.output);
  const allowed = layout(t, clean, [caller]);
  allowed.put(caller, source);
  const accepted = allowed.run();
  assert.equal(accepted.status, 0, accepted.output);
});

test('inventory includes the exact fixture path without changing the old source categories', t => {
  const fixture = layout(t, '(ns probe)\n(defn value [x] (aget x "x"))\n');
  fixture.put(caller, '(ns probe)\n(defn value [x] (aget x "x"))\n');
  const result = fixture.run('--inventory');
  assert.equal(result.status, 0, result.output);
  const rows = JSON.parse(result.stdout);
  assert.ok(rows.some(row => row.file === entryPath && row.counts.aget === 1), result.output);
  assert.ok(rows.some(row => row.file === caller && row.counts.aget === 1), result.output);
});
