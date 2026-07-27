# Signed division comparison soundness reproducer

Run from this directory with a locally built prover:

```sh
certoraRun.py Default.conf
```

Before the fix, `cvl_mirror_must_have_counterexample` incorrectly returned
`SUCCESS`. After the fix, the important results are:

| Rule | Result |
| --- | --- |
| `solidity_semantics_has_counterexample` | `FAIL` at `x = -1`, `result = 0` |
| `cvl_mirror_must_have_counterexample` | `FAIL` at `x = -1` |
| `quotient_of_minus_one_is_zero` | `SUCCESS` |

The unsound `divLt` optimizer rewrite changed `x / 2 < 0` into `x < 0`. That
identity is valid for non-negative integer division (floor and truncation
coincide there), but not for a negative dividend when `IntDiv` rounds toward
zero.

The fix allows these division-comparison rewrites for
`IntDiv` only when the dividend is known non-negative.  The existing
`isSurelyNonNeg()` helper already returns true for all `Tag.Bits` values, so
using it as an additional guard preserves all current unsigned `Div` rewrites.
