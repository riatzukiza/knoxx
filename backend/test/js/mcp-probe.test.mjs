import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseCommand } from '../../scripts/mcp-probe.mjs';

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
