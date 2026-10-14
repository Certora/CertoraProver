/// Tests for the hashing model: injectivity, region separation ("large gaps"),
/// root-slot reservation, and the wrap-around behavior of storage-pointer
/// arithmetic.
///
/// The FAIL-expected rules mark the boundaries of the model: places where
/// aliasing is genuinely possible and the model must not separate.

methods {
    function scalarA() external returns (uint256) envfree;
    function scalarB() external returns (uint256) envfree;
    function mapA(uint256) external returns (uint256) envfree;
    function mapB(uint256) external returns (uint256) envfree;
    function arrA(uint256) external returns (uint256) envfree;
    function arrB(uint256) external returns (uint256) envfree;
    function setMapA(uint256, uint256) external envfree;
    function setMapB(uint256, uint256) external envfree;
    function setArrA(uint256, uint256) external envfree;
    function setArrB(uint256, uint256) external envfree;
    function pushArrA(uint256) external envfree;
    function arrALength() external returns (uint256) envfree;
    function setPairFst(uint256, uint256) external envfree;
    function setPairSnd(uint256, uint256) external envfree;
    function setNested(uint256, uint256, uint256) external envfree;
    function pairs(uint256) external returns (uint256, uint256) envfree;
    function nested(uint256, uint256) external returns (uint256) envfree;
    function setGrid(uint256, uint256) external envfree;
    function rawWriteArrA(uint256, uint256) external envfree;
    function rawReadArrA(uint256) external returns (uint256) envfree;
    function fillThenDelete(uint256, uint256) external envfree;
    function keccakOf(uint256) external returns (uint256) envfree;
    function keccakOf2(uint256, uint256) external returns (uint256) envfree;
    function sha256Of(uint256) external returns (uint256) envfree;
    function sha256Of2(uint256, uint256) external returns (uint256) envfree;
}

/// ---------------------------------------------------------------------------
/// injectivity: writes through one mapping/array never affect another root
/// ---------------------------------------------------------------------------

/// needs injectivity of the hash in its slot argument (mapA is slot 2, mapB slot 3)
rule mapWriteDoesNotTouchOtherMap(uint256 k1, uint256 k2, uint256 v) {
    uint256 before = mapB(k2);
    setMapA(k1, v);
    assert mapB(k2) == before;
}

/// two different keys of the same mapping don't alias -- injectivity in the key argument
rule mapWriteDoesNotTouchOtherKey(uint256 k1, uint256 k2, uint256 v) {
    require k1 != k2;
    uint256 before = mapA(k2);
    setMapA(k1, v);
    assert mapA(k2) == before;
}

/// FAIL expected: without `k1 != k2` the same slot can be written -- the model
/// must not separate equal keys
rule mapSameKeyAliases(uint256 k1, uint256 k2, uint256 v) {
    uint256 before = mapA(k2);
    setMapA(k1, v);
    assert mapA(k2) == before;
}

/// ---------------------------------------------------------------------------
/// large gaps: offsets on top of different hashes never collide
/// ---------------------------------------------------------------------------

/// needs region separation: arrA[i] is hash(4)+i, arrB[j] is hash(5)+j, and
/// i, j are only bounded by the array lengths -- injectivity alone (hash(4) != hash(5))
/// does not rule out hash(4)+i == hash(5)+j
rule arrayWriteDoesNotTouchOtherArray(uint256 i, uint256 j, uint256 v) {
    uint256 before = arrB(j);
    setArrA(i, v);
    assert arrB(j) == before;
}

/// FAIL expected: elements of the *same* array at unconstrained indices can alias
rule arraySameIndexAliases(uint256 i, uint256 j, uint256 v) {
    uint256 before = arrA(j);
    setArrA(i, v);
    assert arrA(j) == before;
}

/// needs region separation between two struct instances in the same mapping:
/// pairs[k1].snd is hash(k1,6)+1 and pairs[k2].fst is hash(k2,6) -- ruling out
/// hash(k1,6)+1 == hash(k2,6) is exactly the large-gaps property
rule structFieldsOfDifferentKeysDoNotAlias(uint256 k1, uint256 k2, uint256 v) {
    uint256 beforeFst;
    uint256 beforeSnd;
    beforeFst, beforeSnd = pairs(k2);
    setPairSnd(k1, v);
    uint256 afterFst;
    uint256 afterSnd;
    afterFst, afterSnd = pairs(k2);
    assert afterFst == beforeFst;
    assert k1 != k2 => afterSnd == beforeSnd;
}

/// nested mappings: hash(k2, hash(k1, 7)) -- needs injectivity through the
/// recursive hash argument
rule nestedMappingSeparation(uint256 k1, uint256 k2, uint256 j1, uint256 j2, uint256 v) {
    require k1 != k2;
    uint256 before = nested(k2, j2);
    setNested(k1, j1, v);
    assert nested(k2, j2) == before;
}

/// ---------------------------------------------------------------------------
/// root-slot reservation: hash-derived pointers never hit scalar slots
/// ---------------------------------------------------------------------------

/// needs `hash > maxSoliditySlot` plus region separation for the offset
rule arrayWriteDoesNotTouchScalars(uint256 i, uint256 v) {
    uint256 beforeA = scalarA();
    uint256 beforeB = scalarB();
    setArrA(i, v);
    assert scalarA() == beforeA && scalarB() == beforeB;
}

rule mapWriteDoesNotTouchScalars(uint256 k, uint256 v) {
    uint256 beforeA = scalarA();
    setMapA(k, v);
    assert scalarA() == beforeA;
}

/// ---------------------------------------------------------------------------
/// wrap-around (mod 2^256): raw pointer arithmetic with unconstrained offsets
/// ---------------------------------------------------------------------------

/// documents a deliberate model assumption: `dataSlot(arrA) + idx` is treated
/// as staying in arrA's hash region for EVERY idx, although on the real EVM a
/// huge idx wraps around 2^256 and can hit slot 0. The hashing model has
/// always assumed such collisions away; this pins that behavior.
rule rawWriteStaysInItsRegion_modelAssumption(uint256 idx, uint256 v) {
    uint256 before = scalarA();
    rawWriteArrA(idx, v);
    assert scalarA() == before;
}

/// the raw pointer computation is deterministic: a write followed by a read
/// through the same computation returns the written value
rule rawWriteReadRoundtrip(uint256 idx, uint256 v) {
    rawWriteArrA(idx, v);
    assert rawReadArrA(idx) == v;
}

/// FAIL expected: two raw writes at unconstrained offsets can alias
rule rawWritesCanAlias(uint256 idx1, uint256 idx2, uint256 v1, uint256 v2) {
    rawWriteArrA(idx1, v1);
    rawWriteArrA(idx2, v2);
    assert rawReadArrA(idx1) == v1;
}

/// ---------------------------------------------------------------------------
/// storage comparison: FAIL expected -- these exist to exercise the
/// counterexample display for storage-comparison witnesses (the witness's
/// integer model value gets its skey structure reconstructed by matching
/// against the program's hash applications; the call trace should point at
/// the changed mapping/array location)
/// ---------------------------------------------------------------------------

rule storageComparisonFindsMapKey(uint256 k, uint256 v) {
    storage init = lastStorage;
    setMapA(k, v);
    assert init == lastStorage;
}

rule storageComparisonFindsArrayElem(uint256 i, uint256 v) {
    storage init = lastStorage;
    setArrA(i, v);
    assert init == lastStorage;
}

/// ---------------------------------------------------------------------------
/// hash families: keccak and sha256 are both injective and deterministic;
/// applications of different arities are separated through their length
/// argument; different families are unrelated
/// ---------------------------------------------------------------------------

rule keccakInjective(uint256 x, uint256 y) {
    require x != y;
    assert keccakOf(x) != keccakOf(y);
}

rule keccakDeterministic(uint256 x) {
    assert keccakOf(x) == keccakOf(x);
}

/// hashes of different input lengths (32 vs 64 bytes) never collide -- the
/// byte length is an argument of the modeled hash, covered by injectivity
rule keccakDifferentLengthsDiffer(uint256 x, uint256 y, uint256 z) {
    assert keccakOf(x) != keccakOf2(y, z);
}

/// non-keccak families get injectivity and determinism as well (they just
/// don't participate in the storage-region machinery)
rule sha256Injective(uint256 x, uint256 y) {
    require x != y;
    assert sha256Of(x) != sha256Of(y);
}

rule sha256Deterministic(uint256 x) {
    assert sha256Of(x) == sha256Of(x);
}

rule sha256DifferentLengthsDiffer(uint256 x, uint256 y, uint256 z) {
    assert sha256Of(x) != sha256Of2(y, z);
}

/// FAIL expected: different hash families are unrelated uninterpreted
/// functions -- the model allows keccak(x) == sha256(x)
rule keccakVsSha256Unrelated(uint256 x) {
    assert keccakOf(x) != sha256Of(x);
}

/// hash results always exceed the reserved root-slot range (the
/// `maxSoliditySlot` bound, 10000 by default) -- also when the value escapes
/// into ordinary arithmetic
rule keccakAboveRootSlots(uint256 x) {
    assert keccakOf(x) > 10000;
}

/// ---------------------------------------------------------------------------
/// storage-key differences: solc's array-clear loop subtracts storage pointers;
/// this rule is a canary against over-constraining key arithmetic (it went
/// vacuous when `base(key1 - key2) == base(key1)` was wrongly emitted)
/// ---------------------------------------------------------------------------

rule fillThenDeleteReachable(uint256 v, uint256 len) {
    require len > 0 && len <= 3;
    fillThenDelete(v, len);
    assert arrALength() == 0;
}

/// ---------------------------------------------------------------------------
/// hook index recovery on wrapping pointers: an `Sstore` hook recovers its
/// indices by *inverting* the pointer arithmetic (subtract the data slot, then
/// div/mod by the strides). Storage-pointer additions wrap mod 2^256, so the
/// inverting subtraction has to wrap too -- with a non-wrapping subtraction the
/// recovered index is garbage whenever `dataSlot(grid) + 6*i` crosses 2^256,
/// and only for non-power-of-two strides (a power of two divides 2^256, so the
/// wrap cancels inside the mod).
/// ---------------------------------------------------------------------------

ghost mapping(uint => mathint) gridWrites;

hook Sstore grid[INDEX uint256 i][INDEX uint256 j][INDEX uint256 k] uint v {
    gridWrites[j] = gridWrites[j] + 1;
}

/// `i` is bounded so that the *outer* index is unambiguous: with a fully
/// unconstrained `i` even `6*i` alone can wrap, which the model has always
/// allowed (this rule would then fail regardless of the recovery arithmetic).
rule hookIndexRecoveryOnWrappingPointer(uint256 i, uint256 v) {
    require i < 1000;
    require gridWrites[1] == 0;
    setGrid(i, v);
    assert gridWrites[1] == 1;
}
