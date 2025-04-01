#!/bin/bash

# The first argument to this script should be the path to the CertoraProver/bin directory

cd fried-egg && cargo build --release && cd ../

dir=${1}

rm "$dir/tac_optimizer" 2>/dev/null || true
mv "fried-egg/target/release/tac_optimizer" "$dir"
