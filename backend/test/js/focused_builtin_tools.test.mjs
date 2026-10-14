import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { access, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

// The real SDK is imported only inside a child with an allowlisted environment,
// isolated home/agent directories and fixture cwd. No provider is invoked.
const workerFlag = "--focused-builtin-fixture";

function isolatedEnvironment(root) {
  return {
    PATH: "/usr/bin:/bin",
    HOME: join(root, "home"),
    XDG_CONFIG_HOME: join(root, "home", ".config"),
    XDG_CACHE_HOME: join(root, "home", ".cache"),
    TMPDIR: join(root, "tmp"),
    SHELL: "/bin/bash",
    LANG: "C.UTF-8",
    TERM: "dumb",
    PI_CODING_AGENT_DIR: join(root, "agent-a"),
  };
}

function deepFreeze(value) {
  if (value && typeof value === "object") {
    for (const child of Object.values(value)) deepFreeze(child);
    Object.freeze(value);
  }
  return value;
}

function validateUnchanged(validator, input, expectedValid) {
  const before = JSON.stringify(input);
  deepFreeze(input);
  const result = validator(input);
  assert.equal(result.valid, expectedValid, "trusted schema must decide before execution");
  assert.equal(JSON.stringify(input), before, "validation must not mutate, default or coerce input");
  if (expectedValid) assert.equal(result.data, input, "validated data preserves the original value");
  else assert.equal(result.data, undefined, "invalid data is not admitted");
}

function resultText(result) {
  return result.content.filter((part) => part.type === "text").map((part) => part.text).join("\n");
}

function validateBuiltinSchemas(tools, AjvJsonSchemaValidator, Ajv, addFormats) {
  const ajv = new Ajv({ strictSchema: true, strictTypes: false, strictTuples: false,
    strictRequired: false, validateSchema: true, validateFormats: true, allErrors: true,
    coerceTypes: false, useDefaults: false, removeAdditional: false });
  addFormats(ajv);
  const provider = new AjvJsonSchemaValidator(ajv);
  const validArguments = {
    read: { path: "fixture.txt", offset: 1, limit: 1 },
    write: { path: "nested/output.txt", content: "first line\nsecond line\n" },
    edit: { path: "nested/output.txt", edits: [{ oldText: "first line", newText: "updated line" }] },
    bash: { command: "printf 'focused builtin fixture\\n'; pwd", timeout: 5 },
  };
  const wrongArguments = {
    read: [{ path: 7 }, { path: "fixture.txt", offset: "1" }, { path: "fixture.txt", limit: "1" }],
    write: [{ path: 7, content: "text" }, { path: "fixture.txt", content: 7 }],
    edit: [{ path: "fixture.txt", edits: "[]" },
      { path: "fixture.txt", edits: [{ oldText: "text", newText: 7 }] }],
    bash: [{ command: 7 }, { command: "pwd", timeout: "5" }],
  };
  const closedBuiltins = [];
  const openBuiltins = [];
  const validators = {};
  for (const tool of tools) {
    assert.equal(typeof tool.execute, "function", `${tool.name} keeps its real public execution closure`);
    const schema = deepFreeze(JSON.parse(JSON.stringify(tool.parameters)));
    assert.equal(schema.type, "object");
    const schemaBefore = JSON.stringify(schema);
    const validate = provider.getValidator(schema);
    validators[tool.name] = validate;
    validateUnchanged(validate, structuredClone(validArguments[tool.name]), true);
    validateUnchanged(validate, {}, false);
    for (const input of wrongArguments[tool.name]) validateUnchanged(validate, input, false);
    const closed = schema.additionalProperties === false;
    validateUnchanged(validate, { ...structuredClone(validArguments[tool.name]), fixtureExtra: true }, !closed);
    (closed ? closedBuiltins : openBuiltins).push(tool.name);
    assert.equal(JSON.stringify(schema), schemaBefore, "schema compilation must preserve trusted declarations");
  }
  assert.deepEqual(closedBuiltins, ["edit"], "closed behavior comes from the installed edit declaration");
  validateUnchanged(validators.edit, {
    path: "fixture.txt", edits: [{ oldText: "text", newText: "other", fixtureExtra: true }],
  }, false);
  // The installed four builtin schemas declare no enums. A separate trusted
  // foreign-schema fixture proves enum enforcement without inventing a builtin
  // restriction or replacing its actual schema with a hand-written version.
  const declaredEnum = provider.getValidator(deepFreeze({
    type: "object", required: ["selection"], additionalProperties: false,
    properties: { selection: { type: "string", enum: ["read", "write"] } },
  }));
  validateUnchanged(declaredEnum, { selection: "read" }, true);
  validateUnchanged(declaredEnum, { selection: "unsupported" }, false);
  for (const schema of [{ type: "object", required: "field" },
    { type: "object", unknownValidationKeyword: true },
    { type: "object", unevaluatedProperties: false }]) {
    assert.throws(() => provider.getValidator(schema), "malformed/unsupported draft07 contracts refuse compilation");
  }
  return { validArguments, validators, closedBuiltins, openBuiltins, enumFixtureValidated: true };
}

async function executeBuiltinFixtures(root, tools, validation) {
  const workspace = join(root, "workspace");
  const byName = Object.fromEntries(tools.map((tool) => [tool.name, tool]));
  const run = async (name, args) => {
    validateUnchanged(validation.validators[name], args, true);
    return byName[name].execute(`fixture-${name}`, args, undefined, undefined);
  };
  assert.notEqual(process.cwd(), workspace, "factories bind cwd independently of the process cwd");
  await writeFile(join(workspace, "fixture.txt"), "workspace source fixture\n", "utf8");
  await writeFile(join(root, "launcher", "fixture.txt"), "wrong process cwd fixture\n", "utf8");
  const initialRead = resultText(await run("read", { path: "fixture.txt" }));
  assert.match(initialRead, /workspace source fixture/);
  assert.doesNotMatch(initialRead, /wrong process cwd fixture/);
  await run("write", structuredClone(validation.validArguments.write));
  assert.equal(await readFile(join(workspace, "nested", "output.txt"), "utf8"), "first line\nsecond line\n");
  await assert.rejects(access(join(root, "launcher", "nested", "output.txt")));
  const editResult = await run("edit", structuredClone(validation.validArguments.edit));
  assert.equal(await readFile(join(workspace, "nested", "output.txt"), "utf8"), "updated line\nsecond line\n");
  assert.match(editResult.details.diff, /^\+\d+ updated line$/m);
  const boundedRead = resultText(await run("read", { path: "nested/output.txt", offset: 2, limit: 1 }));
  assert.match(boundedRead, /second line/);
  assert.doesNotMatch(boundedRead, /updated line/);
  const shellText = resultText(await run("bash", structuredClone(validation.validArguments.bash)));
  assert.match(shellText, /focused builtin fixture/);
  assert.ok(shellText.split("\n").includes(await realpath(workspace)), "real bash executes inside the factory cwd");
  return { read: true, write: true, edit: true, bash: true, cwdBound: true };
}

async function verifySessionOwnedLoader(root, sdk) {
  const { DefaultResourceLoader, SettingsManager } = sdk;
  const workspace = join(root, "workspace");
  const resourceDir = join(workspace, ".ημ");
  await mkdir(join(resourceDir, "extensions"), { recursive: true });
  await mkdir(join(resourceDir, "skills", "fixture"), { recursive: true });
  await mkdir(join(resourceDir, "prompts"), { recursive: true });
  await writeFile(join(resourceDir, "extensions", "must-not-load.mjs"),
    "throw new Error('Fixture extension unexpectedly loaded');\n", "utf8");
  await writeFile(join(resourceDir, "skills", "fixture", "SKILL.md"), "Fixture skill must stay unloaded.\n", "utf8");
  await writeFile(join(resourceDir, "prompts", "must-not-load.md"), "Fixture prompt must stay unloaded.\n", "utf8");
  await writeFile(join(workspace, "AGENTS.md"), "Fixture context must stay unloaded.\n", "utf8");
  const flags = {
    noExtensions: true, noSkills: true, noPromptTemplates: true,
    noThemes: true, noContextFiles: true,
  };
  const persona = "Explicit fixture creator persona.";
  let snapshot = "Fixture admitted snapshot revision one.";
  const bases = [];
  const loader = new DefaultResourceLoader({
    cwd: workspace, agentDir: join(root, "agent-a"),
    settingsManager: SettingsManager.inMemory({}), ...flags,
    systemPrompt: persona, appendSystemPrompt: [],
    systemPromptOverride: (base) => { bases.push(base); return `${base}\n\n${snapshot}`; },
  });
  // Check the installed implementation recognizes every disabling option
  // before any reload. A missing flag stops the unsafe part explicitly.
  if (!Object.keys(flags).every((key) => loader[key] === true)) {
    return { skipped: true, reason: "installed loader lacks required resource isolation flags" };
  }
  const otherPersona = "Independent fixture creator persona.";
  const otherSnapshot = "Independent fixture admitted snapshot.";
  const otherLoader = new DefaultResourceLoader({
    cwd: workspace, agentDir: join(root, "agent-b"),
    settingsManager: SettingsManager.inMemory({}), ...flags,
    systemPrompt: otherPersona, appendSystemPrompt: [],
    systemPromptOverride: (base) => `${base}\n\n${otherSnapshot}`,
  });
  await loader.reload();
  await otherLoader.reload();
  assert.equal(loader.getSystemPrompt(), `${persona}\n\n${snapshot}`);
  assert.equal(otherLoader.getSystemPrompt(), `${otherPersona}\n\n${otherSnapshot}`);
  snapshot = "Fixture admitted snapshot revision two.";
  await loader.reload();
  await otherLoader.reload();
  assert.equal(loader.getSystemPrompt(), `${persona}\n\n${snapshot}`);
  assert.equal(otherLoader.getSystemPrompt(), `${otherPersona}\n\n${otherSnapshot}`);
  assert.deepEqual(bases, [persona, persona]);
  for (const owned of [loader, otherLoader]) {
    assert.deepEqual(owned.getExtensions().extensions, []);
    assert.deepEqual(owned.getExtensions().errors, []);
    assert.deepEqual(owned.getSkills().skills, []);
    assert.deepEqual(owned.getPrompts().prompts, []);
    assert.deepEqual(owned.getThemes().themes, []);
    assert.deepEqual(owned.getAgentsFiles().agentsFiles, []);
    assert.deepEqual(owned.getAppendSystemPrompt(), []);
  }
  return { skipped: false, personaPreserved: true, snapshotUpdated: true, sessionIsolation: true, resourcesDisabled: true };
}

async function verifyRealSessionBoundary(root, sdk) {
  const workspace = join(root, "workspace");
  const settingsManager = sdk.SettingsManager.inMemory({});
  const authStorage = sdk.AuthStorage.inMemory({});
  const modelRegistry = sdk.ModelRegistry.inMemory(authStorage);
  const sessionManager = sdk.SessionManager.inMemory(workspace);
  const history = [{ role: "user", content: "Earlier character encounter", timestamp: 1 },
    { role: "user", content: "Later retained decision", timestamp: 2 }];
  for (const message of history) sessionManager.appendMessage(message);
  const persona = "Existing fixture creator identity.";
  const projection = "Supplied persistent field projection and admitted evidence.";
  const systemPrompt = `${persona}\n\n${projection}`;
  const resourceLoader = new sdk.DefaultResourceLoader({ cwd: workspace,
    agentDir: join(root, "agent-a"), settingsManager,
    noExtensions: true, noSkills: true, noPromptTemplates: true, noThemes: true, noContextFiles: true,
    systemPromptOverride: () => systemPrompt });
  await resourceLoader.reload();
  const customTools = ["capabilities", "invoke", "read"].map((name) => ({
    name, label: name, description: "Fixture surface", parameters: { type: "object" },
    execute: async () => { throw new Error("No fixture tool executes during session construction"); },
  }));
  const { session } = await sdk.createAgentSession({ cwd: workspace, agentDir: join(root, "agent-a"),
    authStorage, modelRegistry, sessionManager, settingsManager, resourceLoader, customTools,
    tools: ["capabilities", "invoke"], thinkingLevel: "off",
    model: { id: "fixture", name: "Fixture", provider: "fixture", api: "openai-completions",
      baseUrl: "https://example.invalid", reasoning: false, input: ["text"],
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 8192, maxTokens: 100 } });
  try {
    assert.deepEqual(session.messages, history, "real SessionManager restores prior messages");
    assert.deepEqual(session.getActiveToolNames(), ["capabilities", "invoke"]);
    const restored = session.messages;
    for (const selection of [["capabilities"], [], ["read"], ["capabilities", "invoke"]]) {
      session.setActiveToolsByName(selection);
      assert.deepEqual(session.getActiveToolNames(), selection.filter((name) => name !== "read"));
      assert.equal(session.messages, restored, "supported tool changes retain the same message history");
      assert.equal(session.agent.state.systemPrompt.split(persona).length - 1, 1);
      assert.ok(session.agent.state.systemPrompt.includes(projection));
    }
    await session.reload();
    assert.deepEqual(session.getActiveToolNames(), ["capabilities", "invoke"], "refresh keeps the creation ceiling");
    assert.equal(session.messages, restored);
    assert.equal(session.agent.state.systemPrompt.split(persona).length - 1, 1);
    assert.ok(session.agent.state.systemPrompt.includes(projection));
    assert.deepEqual(session.getAllTools().map(({ name }) => name).sort(), ["capabilities", "invoke"]);
    return { historyRestored: true, modeContinuity: true, registryCeiling: true, promptRebuild: true };
  } finally {
    session.dispose();
  }
}

async function fixtureWorker(root) {
  let networkAttempts = 0;
  const refuseNetwork = () => { networkAttempts += 1; throw new Error("Fixture prohibits network/API access"); };
  const { default: http } = await import("node:http");
  const { default: https } = await import("node:https");
  const { default: net } = await import("node:net");
  const { syncBuiltinESMExports } = await import("node:module");
  http.request = http.get = https.request = https.get = refuseNetwork;
  net.connect = net.createConnection = net.Socket.prototype.connect = refuseNetwork;
  globalThis.fetch = refuseNetwork;
  syncBuiltinESMExports();
  const sdk = await import("@open-hax/eta-mu-cli");
  const { AjvJsonSchemaValidator } = await import("@modelcontextprotocol/sdk/validation/ajv");
  const { default: Ajv } = await import("ajv");
  const { default: addFormats } = await import("ajv-formats");
  const workspace = join(root, "workspace");
  const tools = [sdk.createReadTool(workspace), sdk.createWriteTool(workspace),
    sdk.createEditTool(workspace), sdk.createBashTool(workspace, { shellPath: "/bin/bash" })];
  assert.deepEqual(tools.map((tool) => tool.name), ["read", "write", "edit", "bash"]);
  const validation = validateBuiltinSchemas(tools, AjvJsonSchemaValidator, Ajv, addFormats);
  const builtins = await executeBuiltinFixtures(root, tools, validation);
  const loader = await verifySessionOwnedLoader(root, sdk);
  const session = await verifyRealSessionBoundary(root, sdk);
  assert.equal(networkAttempts, 0, "no model/provider/API/network operation was attempted");
  return { builtins, loader, session, networkAttempts, validation: {
    closedBuiltins: validation.closedBuiltins, openBuiltins: validation.openBuiltins,
    enumFixtureValidated: validation.enumFixtureValidated,
  } };
}

async function runIsolatedWorker(root) {
  const child = spawn(process.execPath, [fileURLToPath(import.meta.url), workerFlag, root], {
    cwd: join(root, "launcher"), env: isolatedEnvironment(root), stdio: ["ignore", "pipe", "pipe"],
  });
  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (chunk) => { stdout += chunk; });
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  const timeout = setTimeout(() => child.kill("SIGKILL"), 20000);
  try {
    const code = await new Promise((resolve, reject) => {
      child.once("error", reject);
      child.once("close", resolve);
    });
    assert.equal(code, 0, `isolated SDK fixture failed: ${stderr}`);
    return JSON.parse(stdout.trim());
  } finally {
    clearTimeout(timeout);
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
  }
}

if (process.argv[2] === workerFlag) {
  try {
    process.stdout.write(`${JSON.stringify(await fixtureWorker(process.argv[3]))}\n`);
  } catch (error) {
    console.error(error);
    process.exitCode = 1;
  }
} else {
  test("real SDK builtin closures, trusted foreign schemas and owned prompt reload work without models", async (t) => {
    const root = await mkdtemp(join(tmpdir(), "knoxx-focused-builtins-"));
    try {
      for (const directory of ["workspace", "launcher", "home", "tmp", "agent-a", "agent-b"]) {
        await mkdir(join(root, directory), { recursive: true });
      }
      const result = await runIsolatedWorker(root);
      assert.deepEqual(result.builtins, { read: true, write: true, edit: true, bash: true, cwdBound: true });
      assert.deepEqual(result.validation.closedBuiltins, ["edit"]);
      assert.deepEqual(result.validation.openBuiltins, ["read", "write", "bash"]);
      assert.equal(result.validation.enumFixtureValidated, true);
      assert.equal(result.networkAttempts, 0);
      assert.deepEqual(result.session, { historyRestored: true, modeContinuity: true,
        registryCeiling: true, promptRebuild: true });
      if (result.loader.skipped) t.diagnostic(`Prompt reload omitted: ${result.loader.reason}`);
      else assert.deepEqual(result.loader, { skipped: false, personaPreserved: true,
        snapshotUpdated: true, sessionIsolation: true, resourcesDisabled: true });
    } finally {
      await rm(root, { recursive: true, force: true });
    }
    await assert.rejects(access(root), "the owned fixture directory is removed even after validation failure");
  });
}
