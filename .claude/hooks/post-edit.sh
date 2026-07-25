#!/bin/bash
# Post-edit hook — format or validate after file changes
INPUT=$(cat)
FILE=$(echo "$INPUT" | jq -r '.tool_input.file_path')
# Add project-specific formatting here

