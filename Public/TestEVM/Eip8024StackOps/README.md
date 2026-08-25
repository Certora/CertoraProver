# EIP-8024: DUPN / SWAPN / EXCHANGE

No released Solidity or Vyper compiler emits `DUPN` (0xe6), `SWAPN` (0xe7) or
`EXCHANGE` (0xe8), so the test contract is hand-assembled and passed to solc
through Yul's `verbatim`, which the Prover already supports via `yul_abi`
(see `Test/TestEVM/Yul`).

`verbatim` cannot receive these instructions' operands as inputs: they sit
deeper than the 16 stack slots solc's allocator can marshal, and asking for
more fails with `Variable ... is too deep inside the stack`. Each blob
therefore builds its own deep stack, applies one instruction, and unwinds back
to a single value, so solc only ever sees an opaque instruction taking at most
two inputs.

## Regenerating

```bash
python3 ./generate.py            # rewrite Eip8024.yul and Eip8024ABI.json, then verify
python3 ./generate.py --verify   # verify the checked-in Eip8024.yul only
```

`generate.py` assembles each blob against a transcription of the EIP-8024
semantics, so the values `Eip8024.spec` expects come from the spec rather than
from a hand-trace, and then recompiles the result and asserts that every
instruction under test survives with the immediate it was given.

That last check is not paranoia: solc's assembly optimizer used to merge
distinct `verbatim` blobs that share an arity (broken through 0.8.22, fixed in
0.8.23), which compiles cleanly while pointing several selectors at the wrong
instruction. `Default.conf` pins `solc8.35` and leaves `solc_optimize` off, so
the deployed bytecode is exactly what `generate.py` assembled.

## Coverage

The dispatcher itself routes the selector through one `DUPN`, one `SWAPN` and one
`EXCHANGE` before the `switch`. That covers a second consumer: `DispatchAnalysis`
reads the dispatcher to work out which selector reaches which handler, and
`Decompiler` builds one TAC program per method from what it finds. If any of the
three is not modelled there the selector becomes unknown, no method path is
recovered, and *every* rule fails rather than one -- which is what tells you the
dispatcher rather than a case body is at fault.

The case bodies below each cover one instruction in the decompiler proper.


| function | instruction | why |
|---|---|---|
| `dupn` | `DUPN 18`, immediate `0x80` | 17 distinct fillers, so an off-by-one decode returns a constant instead of `x` |
| `dupnStopImmediate` | `DUPN 145`, immediate `0x00` | immediate below the reserved range, and `STOP` if decoded as an opcode |
| `dupnSelfdestructImmediate` | `DUPN 144`, immediate `0xff` | immediate above the reserved range, and `SELFDESTRUCT` if decoded as an opcode |
| `swapn` | `SWAPN 17`, immediate `0x80` | `DUP1` if the immediate is decoded as an opcode |
| `exchange` | `EXCHANGE 3 4` | `m <= 16`: first branch of the pair encoding |
| `exchangeDeep` | `EXCHANGE 1 20` | `m > 16`: second branch of the pair encoding |
| `dupnJumpdestImmediate` | `DUPN` + `0x5b` | immediate in the reserved range: exceptional halt |

## Stack depth

`Default.conf` lowers `-recursionLimitHeuristic` from its default of 900 to 700.
`Decompiler` abandons a block once the modelled stack passes
`1025 - recursionLimitHeuristic` slots, so the default tolerates only 125 -
shallower than the 145 slots `dupnStopImmediate` builds, and far shallower than
the 236 that `DUPN 235` would.

Keeping the Prover's default is a deliberate call, so treat this override as
permanent rather than as a gap waiting to be closed. Remove it and
`dupnStopImmediate` and `dupnSelfdestructImmediate` go red with
`StackSizeLimitReached`, an error that names nothing about the instruction that
provoked it. Outside the test the same limit means a contract whose `DUPN`
operands reach past roughly 125 stack slots will not decompile at default
settings; if that ever turns up, the fix is a floor on the guard in `Decompiler`
independent of the flag.

## Reading a failure

`expectedDefault.json` expects every rule to pass. Which one fails narrows down
what broke.

A wrong return value means the immediate decoding is off. The fillers under each
`DUPN` are chosen so that an off-by-one returns a filler rather than the
argument, and `dupnStopImmediate` and `dupnSelfdestructImmediate` fail if the
immediate is decoded as an instruction of its own rather than skipped.

A failing `dupnHaltsOnAnIllegalImmediate` means a reserved immediate is being
executed rather than halting. Watch the width there as well as the halt: the
halt has to stay one byte wide, because EIP-8024 leaves jumpdest analysis alone
and a `JUMPDEST` sitting where a reserved immediate would be is still a legal
jump target.

A `StackSizeLimitReached` from the decompiler is the stack-depth limit above
rather than anything to do with the instructions.

Every rule failing at once points at the dispatcher, not at any one
instruction: no method path was recovered, so nothing was verified.

Deliberately not covered: a `DUPN` whose operand is deeper than the stack.
EIP-8024 calls that an exceptional halt, but `Decompiler` reports any hard stack
underflow as an assertion violation instead, which is long-standing behaviour
shared with `DUP16` on a shallow stack and not specific to these instructions.
