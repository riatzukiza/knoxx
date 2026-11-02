// Run from backend/: node --env-file=<env> scripts/mcp-probe.mjs list|schema <tool>|call <tool> '<json>'
const usage = 'Usage: mcp-probe.mjs list | schema <tool> | call <tool> [json-object]';
export function parseCommand(argv) {
  const [cmd, name, args, ...extra] = argv;
  if (extra.length || !['list', 'schema', 'call'].includes(cmd) ||
      (cmd === 'list' && (name || args)) ||
      (cmd === 'schema' && (!name || args)) ||
      (cmd === 'call' && !name)) throw new Error(usage);
  let parameters;
  if (cmd === 'call') {
    parameters = JSON.parse(args ?? '{}');
    if (!parameters || typeof parameters !== 'object' || Array.isArray(parameters)) {
      throw new Error('Tool arguments must be a JSON object');
    }
  }
  return { cmd, name, parameters };
}

async function main() {
  const { cmd, name, parameters } = parseCommand(process.argv.slice(2));
  if (!process.env.KNOXX_MCP_LOOPBACK_TOKEN) throw new Error('KNOXX_MCP_LOOPBACK_TOKEN is required');

  const { Client } = await import('@modelcontextprotocol/sdk/client/index.js');
  const { StreamableHTTPClientTransport } = await import('@modelcontextprotocol/sdk/client/streamableHttp.js');
  const url = process.env.MCP_URL || 'http://127.0.0.1:8000/mcp';
  const transport = new StreamableHTTPClientTransport(new URL(url), {
    requestInit: { headers: { Authorization: `Bearer ${process.env.KNOXX_MCP_LOOPBACK_TOKEN}` } },
  });
  const client = new Client({ name: 'knoxx-local-probe', version: '0.1' });
  try {
    await client.connect(transport);
    if (cmd === 'list') {
      const { tools } = await client.listTools();
      console.log(tools.length);
      for (const tool of tools) console.log(tool.name);
    } else if (cmd === 'schema') {
      const { tools } = await client.listTools();
      const tool = tools.find(tool => tool.name === name);
      if (!tool) throw new Error(`Unknown tool: ${name}`);
      console.log(JSON.stringify(tool.inputSchema));
    } else {
      const result = await client.callTool({ name, arguments: parameters });
      console.log(JSON.stringify(result));
      if (result.isError) process.exitCode = 1;
    }
  } finally {
    await client.close();
  }
}

if (import.meta.main) {
  main().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
