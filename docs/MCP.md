# MCP (Model Context Protocol) - extension path

v1 ships no MCP client (no fake toggles). The architecture reserves the extension point:

- `ToolRegistry` is the tool boundary; MCP tools would register as `Tool` subclasses with
  declared capabilities/permissions/network requirements and a risk class.
- Policy: an MCP server must be user-added, user-approved per capability, and its tools
  inherit the same risk classifier + confirmation gates as built-ins. No unrestricted
  control for external tools, ever.

Roadmap: stdio-over-local-socket transport to a user-installed MCP host, with per-tool
permission prompts mirroring Android's permission model.
