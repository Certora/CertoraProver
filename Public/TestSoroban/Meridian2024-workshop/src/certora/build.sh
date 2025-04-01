#!/bin/bash

echo "Enter build.sh"
export RUSTFLAGS="-C strip=none --emit=llvm-ir -C debuginfo=2"

echo "Running cargo build..."
if ! cargo build --target=wasm32-unknown-unknown --release --features certora; then
    echo "Cargo build failed"
    exit 1
fi

echo "Running wasm2wat..."
if ! wasm2wat target/wasm32-unknown-unknown/release/certora_meridian24_token.wasm --generate-names -o target/wasm32-unknown-unknown/release/certora_meridian24_token.wat; then
    echo "wasm2wat failed"
    exit 1
fi

echo "Running wat2wasm..."
if ! wat2wasm target/wasm32-unknown-unknown/release/certora_meridian24_token.wat --debug-names -o tmpwasm.wasm; then
    echo "wat2wasm failed"
    exit 1
fi

echo "Running second wasm2wat..."
if ! wasm2wat tmpwasm.wasm -o target/wasm32-unknown-unknown/release/certora_meridian24_token.wat; then
    echo "Second wasm2wat failed"
    exit 1
fi

echo "Removing temporary wasm..."
rm tmpwasm.wasm

echo "Copying wasm to current directory..."
cp target/wasm32-unknown-unknown/release/certora_meridian24_token.wasm .

echo "Exit build.sh"
exit 0
