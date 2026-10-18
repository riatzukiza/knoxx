import { readFile, readdir } from 'node:fs/promises';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const backendRoot = resolve(__dirname, '..');
const repoRoot = resolve(backendRoot, '..');
const sourceRoot = resolve(backendRoot, 'src/cljs/knoxx/backend');
const allowlistPath = resolve(backendRoot, 'config/js-boundary-allowlist.json');
const fixturePath = resolve(backendRoot, 'scripts/translation_split_review_fixture.cljs');

const patternSpecs = [
  ['extern.js import', /knoxx\.backend\.extern\.js/g],
  ['extern.json import', /knoxx\.backend\.extern\.json/g],
  ['xjs/js-array-seq', /xjs\/js-array-seq/g],
  ['xjson/stringify', /xjson\/stringify/g],
  ['xjson/parse-object', /xjson\/parse-object/g],
  ['js->clj', /\bjs->clj\b/g],
  ['clj->js', /\bclj->js\b/g],
  ['js/JSON', /js\/JSON/g],
  ['js/Object', /js\/Object/g],
  ['Object.keys', /\.keys\s+js\/Object|Object\.keys/g],
  ['#js', /#js/g],
  ['aget', /\baget\b/g],
  ['aset', /\baset\b/g],
];

function usage() {
  console.error(`Usage: node backend/scripts/check-js-boundary.mjs [--check|--inventory|--markdown]\n\n--check      enforce generic import allowances and the strict verification fixture boundary\n--inventory  print JSON inventory\n--markdown   print Markdown inventory table`);
}

/** Lex executable tokens without treating comments, strings or character data as code. */
function fixtureTokens(source) {
  const tokens = [];
  const delimiters = [];
  const matching = { ')': '(', ']': '[', '}': '{' };
  let line = 1;
  for (let index = 0; index < source.length;) {
    const character = source[index];
    if (/\s|,/.test(character)) {
      if (character === '\n') line++;
      index++;
    } else if (character === ';') {
      while (index < source.length && source[index] !== '\n') index++;
    } else if (character === '"') {
      const startLine = line;
      let closed = false;
      index++;
      while (index < source.length) {
        const current = source[index++];
        if (current === '\n') line++;
        if (current === '\\') {
          if (source[index] === '\n') line++;
          index++;
        } else if (current === '"') {
          closed = true;
          break;
        }
      }
      if (!closed) throw new Error('unterminated string');
      tokens.push({ type: 'string', line: startLine });
    } else if (character === '\\') {
      // A character literal can itself be a delimiter or a semicolon.
      index++;
      if (index === source.length) throw new Error('unterminated character');
      if ('()[]{};"\\'.includes(source[index])) index++;
      else while (index < source.length && !/[\s,()\[\]{};]/.test(source[index])) index++;
    } else if ('([{'.includes(character)) {
      delimiters.push(character);
      tokens.push({ type: 'open', value: character, line });
      index++;
    } else if (')]}'.includes(character)) {
      if (delimiters.pop() !== matching[character]) throw new Error('mismatched delimiter');
      tokens.push({ type: 'close', value: character, line });
      index++;
    } else {
      const start = index;
      while (index < source.length && !/[\s,()\[\]{};"\\]/.test(source[index])) index++;
      if (index === start) throw new Error('unrecognized token');
      tokens.push({ type: 'symbol', value: source.slice(start, index), line });
    }
  }
  if (delimiters.length) throw new Error('unclosed delimiter');
  return tokens;
}

/** This one entry has no legacy interop allowance; native work belongs to named externs. */
function strictFixtureViolations(source) {
  let tokens;
  try {
    tokens = fixtureTokens(source);
  } catch {
    return [{ category: 'unknown fixture scan', line: 1 }];
  }
  const scopes = [];
  const violations = [];
  const add = (category, token) => violations.push({ category, line: token.line });
  for (const token of tokens) {
    if (token.type === 'open') scopes.push({ delimiter: token.value, head: null });
    else if (token.type === 'close') scopes.pop();
    else if (token.type === 'string') {
      if (scopes.some(scope => scope.head === 'ns') &&
          scopes.some(scope => [':require', ':require-macros', ':import'].includes(scope.head))) {
        add('native module import', token);
      }
    } else {
      const value = token.value;
      const operation = value.slice(value.lastIndexOf('/') + 1);
      const current = scopes.at(-1);
      if (current && current.head === null) current.head = value;
      if (value.startsWith(':')) continue;
      if (/^knoxx\.backend\.extern\.(js|json)(\/|$)/.test(value)) add('generic extern import', token);
      // The entry loads its named adapters statically in ns, never through an
      // executable NBB loader whose package argument could be computed later.
      else if (['require', 'require-macros'].includes(operation)) add('native module import', token);
      else if (/^js\/process(?:[./]|$)/.test(value)) add('native env/process', token);
      else if (/^js\/Date(?:[./]|$)/.test(value)) add('native Date', token);
      else if (/^js\/JSON(?:[./]|$)/.test(value)) add('native JSON', token);
      else if (/^js\/console(?:[./]|$)/.test(value)) add('native console', token);
      else if (['js->clj', 'clj->js'].includes(operation)) add('native conversion', token);
      else if (['aget', 'aset'].includes(operation)) add('native array access', token);
      else if (value === '#js') add('native literal', token);
      else if (value === 'new' || (value.endsWith('.') && !value.startsWith('.'))) add('native construction', token);
      else if (value.startsWith('.')) add('native property/method', token);
      else if (value.startsWith('js/') || value === 'js*') add('native host interop', token);
    }
  }
  return violations;
}

async function readFixtureEntry() {
  try {
    const bytes = await readFile(fixturePath);
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    console.error(`[knoxx] JS boundary check failed: unreadable fixture entry ${relative(repoRoot, fixturePath)}`);
    process.exit(1);
  }
}

async function walk(dir) {
  const entries = await readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const full = resolve(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...await walk(full));
    } else if (entry.isFile() && entry.name.endsWith('.cljs')) {
      files.push(full);
    }
  }
  return files;
}

function countMatches(text, pattern) {
  const matches = text.match(pattern);
  return matches ? matches.length : 0;
}

function isExternPath(relPath) {
  return relPath.startsWith('backend/src/cljs/knoxx/backend/extern/');
}

function genericExternImportCount(counts) {
  return (counts['extern.js import'] || 0) + (counts['extern.json import'] || 0);
}

async function loadAllowlist() {
  try {
    const raw = await readFile(allowlistPath, 'utf8');
    const parsed = JSON.parse(raw);
    const entries = parsed.allowedGenericExternImports || [];
    return new Set(entries.map((entry) => typeof entry === 'string' ? entry : entry.file).filter(Boolean));
  } catch (err) {
    if (err.code === 'ENOENT') {
      return new Set();
    }
    throw err;
  }
}

async function inventory(fixtureSource) {
  const files = await walk(sourceRoot);
  files.push(fixturePath);
  const rows = [];
  for (const file of files) {
    const relPath = relative(repoRoot, file);
    const text = file === fixturePath ? fixtureSource : await readFile(file, 'utf8');
    const counts = Object.fromEntries(patternSpecs.map(([name, pattern]) => [name, countMatches(text, pattern)]));
    const total = Object.values(counts).reduce((sum, n) => sum + n, 0);
    if (total > 0) {
      rows.push({ file: relPath, total, counts });
    }
  }
  rows.sort((a, b) => b.total - a.total || a.file.localeCompare(b.file));
  return rows;
}

function markdown(rows) {
  const headers = ['file', 'total', ...patternSpecs.map(([name]) => name)];
  const lines = [
    `# Knoxx JS/CLJS Boundary Inventory`,
    '',
    `Generated by \`node backend/scripts/check-js-boundary.mjs --markdown\`.`,
    '',
    `| ${headers.join(' | ')} |`,
    `| ${headers.map(() => '---').join(' | ')} |`,
  ];
  for (const row of rows) {
    lines.push(`| ${[row.file, row.total, ...patternSpecs.map(([name]) => row.counts[name] || 0)].join(' | ')} |`);
  }
  return `${lines.join('\n')}\n`;
}

async function check(rows, fixtureSource) {
  const allowlist = await loadAllowlist();
  const violations = [];
  for (const row of rows) {
    // Inventory retains legacy textual counts. This exact entry is decided
    // only by its mandatory token gate, so documentation is not an import.
    if (row.file === relative(repoRoot, fixturePath)) {
      continue;
    }
    if (isExternPath(row.file)) {
      continue;
    }
    if (genericExternImportCount(row.counts) > 0 && !allowlist.has(row.file)) {
      violations.push(row.file);
    }
  }

  if (violations.length > 0) {
    console.error('[knoxx] JS boundary check failed. Non-extern generic extern imports are not allow-listed:');
    for (const file of violations) {
      console.error(`  - ${file}`);
    }
    console.error(`\nAdd intentional active migrations to ${relative(repoRoot, allowlistPath)} or move conversion behind a named boundary adapter.`);
  }

  const fixtureViolations = strictFixtureViolations(fixtureSource);
  if (fixtureViolations.length) {
    console.error('[knoxx] JS boundary check failed. Strict verification fixture boundary:');
    for (const { category, line } of fixtureViolations) {
      console.error(`  - ${relative(repoRoot, fixturePath)}:${line}: ${category}`);
    }
  }
  if (violations.length || fixtureViolations.length) process.exit(1);

  console.log(`[knoxx] JS boundary check passed (${allowlist.size} allow-listed non-extern generic extern import files).`);
}

const mode = process.argv[2] || '--check';
if (!['--check', '--inventory', '--markdown'].includes(mode)) {
  usage();
  process.exit(2);
}

const fixtureSource = await readFixtureEntry();
const rows = await inventory(fixtureSource);
if (mode === '--inventory') {
  console.log(JSON.stringify(rows, null, 2));
} else if (mode === '--markdown') {
  process.stdout.write(markdown(rows));
} else {
  await check(rows, fixtureSource);
}
