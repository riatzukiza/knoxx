import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, copyFileSync, symlinkSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { isEntrypoint, parseCommand } from '../../scripts/mcp-probe.mjs';

test('rejects malformed commands before attempting a connection', () => {
  for (const args of [[], ['bogus'], ['list', 'extra'], ['schema'], ['schema', 'x', '{}'], ['call']]) {
    assert.throws(() => parseCommand(args), /Usage:/, JSON.stringify(args));
  }
});

test('accepts object arguments and rejects invalid or non-object JSON', () => {
  assert.deepEqual(parseCommand(['call', 'tool', '{"x":1}']).parameters, { x: 1 });
  assert.deepEqual(parseCommand(['call', 'tool']).parameters, {});
  for (const args of ['{', 'null', '[]', '1']) {
    assert.throws(() => parseCommand(['call', 'tool', args]), undefined, args);
  }
});


test('direct invocation works through hash paths and symlinks', () => {
  const dir = mkdtempSync(join(tmpdir(), 'mcp-probe-test-'));
  try {
    const script = resolve('scripts/mcp-probe.mjs');
    const hashPath = join(dir, 'probe#test.mjs');
    const linkPath = join(dir, 'probe-link.mjs');
    copyFileSync(script, hashPath);
    symlinkSync(script, linkPath);
    const env = { ...process.env, KNOXX_MCP_LOOPBACK_TOKEN: '' };
    delete env.NODE_TEST_CONTEXT;
    delete env.NODE_OPTIONS;
    for (const path of [hashPath, linkPath]) {
      const result = spawnSync(process.execPath, [path, 'list'], {
        env, encoding: 'utf8',
      });
      assert.equal(result.status, 1);
      assert.match(result.stderr, /KNOXX_MCP_LOOPBACK_TOKEN is required/);
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});


test('fallback entrypoint detection handles missing import.meta.main', () => {
  const dir = mkdtempSync(join(tmpdir(), 'mcp-probe-fallback-'));
  try {
    const script = join(dir, 'probe#test.mjs');
    const link = join(dir, 'link.mjs');
    copyFileSync(resolve('scripts/mcp-probe.mjs'), script);
    symlinkSync(script, link);
    const url = pathToFileURL(script).href;
    assert.equal(isEntrypoint(undefined, link, url), true);
    assert.equal(isEntrypoint(undefined, script, url), true);
    assert.equal(isEntrypoint(undefined, undefined, url), false);
    assert.equal(isEntrypoint(undefined, join(dir, 'absent'), url), false);
    assert.equal(isEntrypoint(false, script, url), false);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
