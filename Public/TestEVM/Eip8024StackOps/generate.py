#      The Certora Prover
#      Copyright (C) 2025  Certora Ltd.
#
#      This program is free software: you can redistribute it and/or modify
#      it under the terms of the GNU General Public License as published by
#      the Free Software Foundation, version 3 of the License.
#
#      This program is distributed in the hope that it will be useful,
#      but WITHOUT ANY WARRANTY; without even the implied warranty of
#      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
#      GNU General Public License for more details.
#
#      You should have received a copy of the GNU General Public License
#      along with this program.  If not, see <https://www.gnu.org/licenses/>.
"""
Generates Eip8024.yul and Eip8024ABI.json, and verifies the compiled bytecode.

No released Solidity or Vyper compiler emits the EIP-8024 instructions, so the
test contract is hand-assembled and handed to solc through Yul's `verbatim`.
The operands of DUPN/SWAPN/EXCHANGE live deeper than the 16 stack slots solc's
allocator can marshal, so a blob cannot receive them as `verbatim` inputs;
instead each blob builds its own deep stack, applies one new instruction, and
unwinds back to a single result. solc only ever sees an opaque 1-in/1-out (or
2-in/1-out) instruction.

`Stack` below transcribes the EIP-8024 semantics directly, and every blob is
executed against it, so the values the CVL rules expect come from the spec
rather than from a hand-trace.

Usage:
    python3 ./generate.py            # regenerate Eip8024.yul + Eip8024ABI.json, then verify
    python3 ./generate.py --verify   # verify the checked-in .yul only
"""
import json
import subprocess
import sys

from Crypto.Hash import keccak

# Must match the "solc" entry in Default.conf. solc <= 0.8.22 merges distinct
# `verbatim` blobs that share an arity, silently pointing several cases at the
# wrong instruction; verify() below is what catches that class of breakage.
SOLC = "solc8.35"

DUPN, SWAPN, EXCHANGE = 0xE6, 0xE7, 0xE8
MNEMONICS = {DUPN: "DUPN", SWAPN: "SWAPN", EXCHANGE: "EXCHANGE"}


def encode_single(n: int) -> int:
    """EIP-8024 immediate encoding for DUPN/SWAPN."""
    assert 17 <= n <= 235, n
    return (n + 111) % 256


def decode_single(imm: int) -> int:
    assert 0 <= imm <= 90 or 128 <= imm <= 255, imm
    return (imm + 145) % 256


def encode_pair(n: int, m: int) -> int:
    """EIP-8024 immediate encoding for EXCHANGE."""
    assert 1 <= n < m and n + m <= 30, (n, m)
    q, r = (n - 1, m - 1) if m <= 16 else (29 - m, n - 1)
    return (16 * q + r) ^ 143


def decode_pair(imm: int) -> tuple:
    assert 0 <= imm <= 81 or 128 <= imm <= 255, imm
    q, r = divmod(imm ^ 143, 16)
    return (q + 1, r + 1) if q < r else (r + 1, 29 - q)


class Stack:
    """EIP-8024 reference semantics over symbolic values. Position 1 is the top."""

    def __init__(self, initial=()):
        self.items = list(initial)  # items[-1] is the top

    def __len__(self):
        return len(self.items)

    def push(self, v):
        self.items.append(v)

    def pop(self):
        return self.items.pop()

    def dupn(self, imm):
        n = decode_single(imm)
        assert n <= len(self.items), f"DUPN {n} underflows depth {len(self.items)}"
        self.push(self.items[-n])

    def swapn(self, imm):
        n = decode_single(imm)
        assert n + 1 <= len(self.items), f"SWAPN {n} underflows depth {len(self.items)}"
        self.items[-1], self.items[-(n + 1)] = self.items[-(n + 1)], self.items[-1]

    def exchange(self, imm):
        n, m = decode_pair(imm)
        assert m + 1 <= len(self.items), f"EXCHANGE {n} {m} underflows depth {len(self.items)}"
        self.items[-(n + 1)], self.items[-(m + 1)] = self.items[-(m + 1)], self.items[-(n + 1)]


class Blob:
    """An EVM byte sequence together with its effect on a symbolic stack."""

    def __init__(self, *inputs):
        # verbatim pushes its first argument last, so inputs[0] ends up on top.
        self.inputs = inputs
        self.stack = Stack(reversed(inputs))
        self.code = bytearray()
        self.asm = []
        self.instructions = []  # (opcode, immediate) pairs under test

    def _emit(self, mnemonic, *code):
        self.code.extend(code)
        self.asm.append(mnemonic)

    def push(self, v):
        assert 0 <= v < 256
        self._emit("PUSH0" if v == 0 else f"PUSH1 0x{v:02x}",
                   *((0x5F,) if v == 0 else (0x60, v)))
        self.stack.push(v)

    def pop(self):
        self._emit("POP", 0x50)
        self.stack.pop()

    def drop_below_top(self, count):
        """Discard `count` items sitting under the top, keeping the top."""
        for _ in range(count):
            self._emit("SWAP1 POP", 0x90, 0x50)
            top = self.stack.pop()
            self.stack.pop()
            self.stack.push(top)

    def _under_test(self, opcode, imm, mnemonic):
        self._emit(mnemonic, opcode, imm)
        self.instructions.append((opcode, imm))

    def dupn(self, n):
        imm = encode_single(n)
        self._under_test(DUPN, imm, f"DUPN {n}")
        self.stack.dupn(imm)

    def swapn(self, n):
        imm = encode_single(n)
        self._under_test(SWAPN, imm, f"SWAPN {n}")
        self.stack.swapn(imm)

    def exchange(self, n, m):
        imm = encode_pair(n, m)
        self._under_test(EXCHANGE, imm, f"EXCHANGE {n} {m}")
        self.stack.exchange(imm)

    def halting(self, opcode, imm, mnemonic):
        """An instruction expected to halt: its stack effect is never observed."""
        self._under_test(opcode, imm, mnemonic)

    def yielding(self, expected):
        assert len(self.stack) == 1, f"expected depth 1, got {len(self.stack)}"
        assert self.stack.items[0] == expected, f"expected {expected}, got {self.stack.items[0]}"
        return self


class Case:
    def __init__(self, name, params, blob, note, halts=False):
        self.name = name
        self.params = params
        self.blob = blob
        self.note = note
        self.halts = halts

    @property
    def signature(self):
        return f"{self.name}({','.join('uint256' for _ in self.params)})"

    @property
    def selector(self):
        h = keccak.new(digest_bits=256)
        h.update(self.signature.encode())
        return h.hexdigest()[:8]

    @property
    def result(self):
        return "halts" if self.halts else self.blob.stack.items[0]


def dupn_case(name, n, filler, note):
    """DUPN reaching `x` from under `n - 1` filler slots."""
    b = Blob("x")
    for i in range(n - 1):
        b.push(filler(i))
    b.dupn(n)
    b.drop_below_top(n)
    return Case(name, ["x"], b.yielding("x"), note)


def swapn_case():
    b = Blob("x", "y")                   # top = x, y beneath it
    for i in range(16):
        b.push(i + 1)                    # depth 18, y is the 18th item
    b.swapn(17)                          # swaps the top with the 18th item
    b.drop_below_top(17)
    return Case("swapn", ["x", "y"], b.yielding("y"),
                "SWAPN 17 lifts the 18th item to the top. Its immediate is 0x80, which "
                "is DUP1 if the immediate is mistaken for an opcode.")


def exchange_case(name, n, m, filler_count, note):
    b = Blob("x", "y")
    for i in range(filler_count):
        b.push(i + 1)
    b.exchange(n, m)
    while b.stack.items[-1] != "y":     # discard whatever EXCHANGE left above y
        b.pop()
    b.drop_below_top(len(b.stack) - 1)
    return Case(name, ["x", "y"], b.yielding("y"), note)


def halting_case(name, opcode, imm, mnemonic, note):
    b = Blob()
    b.halting(opcode, imm, mnemonic)
    return Case(name, [], b, note, halts=True)


CASES = [
    dupn_case("dupn", 18, lambda i: i + 1,
              "DUPN 18 over 17 distinct fillers, so an off-by-one in the immediate "
              "decoding surfaces as a constant rather than as x."),
    dupn_case("dupnStopImmediate", 145, lambda i: 0,
              "DUPN 145 encodes to the immediate 0x00, which is STOP if the byte "
              "following the opcode is not skipped."),
    dupn_case("dupnSelfdestructImmediate", 144, lambda i: 0,
              "DUPN 144 encodes to the immediate 0xff, which is SELFDESTRUCT if the "
              "byte following the opcode is not skipped."),
    swapn_case(),
    # After 3 fillers x is the 4th item and y the 5th, so EXCHANGE 3 4 swaps them.
    exchange_case("exchange", 3, 4, 3,
                  "EXCHANGE 3 4 has m <= 16, exercising the first branch of the pair encoding."),
    # After 19 fillers y is the 21st item, which EXCHANGE 1 20 swaps into position 2.
    exchange_case("exchangeDeep", 1, 20, 19,
                  "EXCHANGE 1 20 has m > 16, exercising the second branch of the pair encoding."),
    halting_case("dupnJumpdestImmediate", DUPN, 0x5B, "DUPN <invalid immediate 0x5b>",
                 "0x5b is not a legal immediate: EIP-8024 reserves it so that jumpdest "
                 "analysis is unaffected, and requires an exceptional halt here."),
]


def dispatch_blob():
    """Routes the selector to the `switch` through one of each instruction.

    The case bodies below cover decompilation, but the dispatcher is read by a
    separate pass: DispatchAnalysis recovers which selector reaches which handler,
    and Decompiler builds one TAC program per method from what it finds. If any of
    these three instructions is not modelled there the selector becomes unknown,
    no method path is recovered, and every rule fails rather than just one.
    """
    b = Blob("selector")
    for i in range(17):
        b.push(i + 1)
    b.dupn(18)                          # copy the selector out from under the fillers
    b.swapn(17)                         # bury it again
    b.exchange(1, 17)                   # and lift it back to just under the top
    b.pop()
    b.drop_below_top(len(b.stack) - 1)
    return b.yielding("selector")


DISPATCH = dispatch_blob()


YUL_TEMPLATE = '''// GENERATED by generate.py -- edit that instead.
//
// Exercises the EIP-8024 instructions DUPN (0xe6), SWAPN (0xe7) and EXCHANGE
// (0xe8), none of which any released compiler emits. Each `verbatim` blob
// builds its own deep stack, applies one instruction, and unwinds back to a
// single value, because the operands of these instructions sit deeper than the
// 16 slots solc's stack allocator can marshal as `verbatim` inputs.
object "Eip8024" {{
    code {{
        datacopy(0, dataoffset("Runtime"), datasize("Runtime"))
        return(0, datasize("Runtime"))
    }}
    object "Runtime" {{
        code {{
            if lt(calldatasize(), 4) {{ revert(0, 0) }}
            // The selector reaches the switch through one DUPN, one SWAPN and one
            // EXCHANGE, so DispatchAnalysis has to model all three to recover any
            // method path at all.
            // {dispatch_asm}
            switch verbatim_1i_1o(hex"{dispatch_hex}", shr(224, calldataload(0)))
{cases}
            default {{ revert(0, 0) }}

            function arg(i) -> v {{ v := calldataload(add(4, mul(32, i))) }}
            function ret(v) {{ mstore(0, v) return(0, 32) }}
        }}
    }}
}}
'''


def collapse(asm):
    """Fold runs of identical mnemonics into `MNEMONIC xN` for the comment."""
    out, run, prev = [], 0, None
    for op in list(asm) + [None]:
        if op == prev:
            run += 1
            continue
        if prev is not None:
            out.append(f"{prev} x{run}" if run > 1 else prev)
        prev, run = op, 1
    return out


def render_case(case):
    args = "".join(f", arg({i})" for i in range(len(case.params)))
    outputs = 0 if case.halts else 1
    call = f'verbatim_{len(case.params)}i_{outputs}o(hex"{case.blob.code.hex()}"{args})'
    # On the halting cases `ret(0)` is unreachable; keeping it means a Prover that
    # models the halt as a normal return fails the rule instead of passing it.
    body = f"{call}\n                ret(0)" if case.halts else f"ret({call})"
    return (f"            // {case.signature} -> {case.result}\n"
            f"            // {case.note}\n"
            f"            // {' '.join(collapse(case.blob.asm))}\n"
            f"            case 0x{case.selector} {{\n"
            f"                {body}\n"
            f"            }}")


def abi_entry(case):
    return {
        "type": "function",
        "name": case.name,
        "stateMutability": "nonpayable",
        "inputs": [{"internalType": "uint256", "type": "uint256", "name": p} for p in case.params],
        "outputs": [{"internalType": "uint256", "type": "uint256", "name": "result"}],
    }


def compile_runtime(path):
    """Compiles `path` the way certoraBuild.py does and returns the runtime bytecode."""
    std_json = {
        "language": "Yul",
        "sources": {path: {"urls": [path]}},
        "settings": {"outputSelection": {"*": {"*": ["evm.deployedBytecode"], "": ["id"]}}},
    }
    result = subprocess.run([SOLC, "--standard-json", "--allow-paths", "."],
                            input=json.dumps(std_json), capture_output=True, text=True)
    output = json.loads(result.stdout)
    errors = [e["formattedMessage"] for e in output.get("errors", []) if e["severity"] == "error"]
    if errors:
        raise SystemExit("\n".join(errors))
    contract, = output["contracts"][path].values()
    return bytes.fromhex(contract["evm"]["deployedBytecode"]["object"])


def scan(code):
    """Linearly disassembles `code`, yielding the (opcode, immediate) pairs under test."""
    offset = 0
    while offset < len(code):
        opcode = code[offset]
        if 0x5F <= opcode <= 0x7F:  # PUSH0..PUSH32
            offset += 1 + (opcode - 0x5F)
        elif opcode in MNEMONICS:
            yield opcode, code[offset + 1]
            offset += 2
        else:
            offset += 1


def verify(path):
    """Asserts every instruction under test survived compilation exactly once.

    solc's assembly optimizer has merged distinct `verbatim` blobs sharing an
    arity (broken up to 0.8.22, fixed in 0.8.23), which leaves the contract
    compiling cleanly while several selectors run the wrong instruction.
    """
    def describe(pair):
        opcode, imm = pair
        operand = decode_pair(imm) if opcode == EXCHANGE else decode_single(imm)
        return f"{MNEMONICS[opcode]} {operand} (0x{opcode:02x} 0x{imm:02x})"

    blobs = [DISPATCH] + [case.blob for case in CASES]
    expected = sorted(i for blob in blobs for i in blob.instructions)
    found = sorted(scan(compile_runtime(path)))
    if expected != found:
        missing = [describe(i) for i in expected if found.count(i) < expected.count(i)]
        extra = [describe(i) for i in found if expected.count(i) < found.count(i)]
        raise SystemExit(f"{SOLC} did not preserve the instructions under test.\n"
                         f"  missing: {missing or 'none'}\n  unexpected: {extra or 'none'}")
    print(f"{path}: {len(found)} instructions under test survived {SOLC}")


if __name__ == "__main__":
    yul, abi = "Eip8024.yul", "Eip8024ABI.json"
    if "--verify" not in sys.argv:
        with open(yul, "w") as f:
            f.write(YUL_TEMPLATE.format(
                dispatch_asm=" ".join(collapse(DISPATCH.asm)),
                dispatch_hex=DISPATCH.code.hex(),
                cases="\n".join(render_case(c) for c in CASES),
            ))
        with open(abi, "w") as f:
            json.dump([abi_entry(c) for c in CASES], f, indent=2)
            f.write("\n")
        print(f"  {'dispatch':38} {'':10} {len(DISPATCH.code):4}B -> selector")
        for case in CASES:
            print(f"  {case.signature:38} 0x{case.selector}  "
                  f"{len(case.blob.code):4}B -> {case.result}")
    verify(yul)
