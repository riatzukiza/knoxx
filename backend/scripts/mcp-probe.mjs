// Minimal MCP client for a local Knoxx: node --env-file=<env> scripts/mcp-probe.mjs list|schema <tool>|call <tool> '<json>'
// Run from backend/: node --env-file=<env> scripts/mcp-probe.mjs list
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
const url = process.env.MCP_URL || 'http://127.0.0.1:8000/mcp';
const t = new StreamableHTTPClientTransport(new URL(url), { requestInit: { headers: { Authorization: `Bearer ${process.env.KNOXX_MCP_LOOPBACK_TOKEN}` } } });
const c = new Client({ name: 'grok-bot-probe', version: '0.1' });
await c.connect(t);
const [cmd, name, args] = process.argv.slice(2);
if (cmd === 'list') { const r = await c.listTools(); console.log(r.tools.length); for (const x of r.tools) console.log(x.name); }
else if (cmd === 'schema') { const r = await c.listTools(); console.log(JSON.stringify(r.tools.find(x=>x.name===name)?.inputSchema)); }
else { const r = await c.callTool({ name, arguments: JSON.parse(args || '{}') }); console.log(JSON.stringify(r).slice(0, 3000)); }
await c.close();
