// PREP ONLY. Pure data constructor: no I/O, imports, subprocess, or model call.
// Selection authority is the exact accepted7112 discoverFreeMiMoFlash function.
export const prompt = "Compatibility check only. Without using tools, calculate (37 * 19) - (23 * 7). Reply with only the decimal integer.";
export const expectedVisibleText = "542";
export const title = "Anonymous MiMo native-thinking compatibility";
export const perRequestOutputCap = 512;

export function prepareCase({ discovery, mode, binary, runRoot, catalogPath, pathEnvironment }) {
  if (!['disabled', 'enabled'].includes(mode)) throw new Error('Unknown case');
  const model = discovery.proof.selected_model;
  const id = discovery.proof.selected_row.id;
  if (model !== `opencode/${id}`) throw new Error('Selected model identity mismatch');
  if (discovery.proof.selected_row.api.npm !== '@ai-sdk/openai-compatible' ||
      discovery.proof.selected_row.api.url !== 'https://opencode.ai/inference/openai/v1') {
    throw new Error('Latest selected model uses a different reviewed SDK/endpoint contract');
  }
  for (const value of [binary, runRoot, catalogPath]) {
    if (typeof value !== 'string' || !value.startsWith('/')) throw new Error('Absolute bound path required');
  }
  const root = `${runRoot}/cases/${mode}`;
  const config = {
    $schema: 'https://opencode.ai/config.json',
    enabled_providers: ['opencode'],
    share: 'disabled',
    autoupdate: false,
    snapshot: false,
    compaction: { auto: false, prune: false },
    permission: { '*': 'deny' },
    plugin: [],
    agent: { build: { steps: 1 } },
    provider: { opencode: { models: { [id]: { options: { thinking: { type: mode } } } } } },
  };
  // Child-only whitelist. No HOME assignment and no inherited credential/override variables.
  const environment = {
    PATH: pathEnvironment,
    LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC',
    XDG_CONFIG_HOME: `${root}/xdg-config`, XDG_DATA_HOME: `${root}/xdg-data`,
    XDG_CACHE_HOME: `${root}/xdg-cache`, XDG_STATE_HOME: `${root}/xdg-state`,
    TMPDIR: `${root}/tmp`,
    OPENCODE_CONFIG: `${root}/config.json`,
    OPENCODE_DISABLE_PROJECT_CONFIG: 'true',
    OPENCODE_DISABLE_AUTOUPDATE: 'true',
    OPENCODE_DISABLE_TERMINAL_TITLE: 'true',
    OPENCODE_MODELS_PATH: catalogPath,
    OPENCODE_DISABLE_MODELS_FETCH: 'true',
    OPENCODE_EXPERIMENTAL_OUTPUT_TOKEN_MAX: String(perRequestOutputCap),
  };
  return {
    role: mode, root, cwd: `${root}/work`, config, environment,
    modelsArgv: [binary, 'models', 'opencode', '--verbose'],
    inferenceArgv: [binary, 'run', '--format', 'json', '--agent', 'build', '--model', model,
      '--title', title, prompt],
    expectedRequestProjection: {
      observationTier: 'source-derived projection, not a wire capture',
      method: 'POST',
      url: `${discovery.proof.selected_row.api.url.replace(/\/$/, '')}/chat/completions`,
      bodyFields: { model: id, max_tokens: perRequestOutputCap, thinking: { type: mode },
        stream: true, stream_options: { include_usage: true } },
      intentionallyAbsent: ['reasoning_effort', 'reasoningEffort', 'max_completion_tokens'],
      messages: 'Native pinned OpenCode system and one literal user prompt; no attachments/history/tools',
    },
  };
}
