#!/bin/bash
# Audit MCP tool calls — append to receipt ledger
INPUT=$(cat)
echo "$INPUT" | jq -c '{ts: now|todate, tool: .tool_name, input: .tool_input}' >> .eta-mu/claude-mcp-audit.jsonl

