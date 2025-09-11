#!/bin/bash

# This build script outputs the JSON information that the entrypoint expects,
# but it does not actually build the code to avoid compilations during testing.
# This build script assumes that the code has been pre-built using
# `just build-sbf`.

# Function to output JSON data with the current working directory as "project_directory"
output_json() {
    local project_directory=$(pwd)  # Get the current working directory
    cat <<EOF
{
    "project_directory": "$project_directory",
    "solana_inlining": [
      "./cvlr_inlining.txt"
    ],
    "solana_summaries": [
      "./cvlr_summaries.txt"
    ],
    "sources": [
      "Cargo.toml",
      "src/**/*.rs"
    ],
    "executables": "rent.so",
    "success": true,
    "return_code": 0,
    "log": null
}
EOF
}

# Check if the --json flag is passed
if [[ "$1" == "--json" ]]; then
    output_json
else
    echo "Usage: $0 --json"
    exit 1
fi
