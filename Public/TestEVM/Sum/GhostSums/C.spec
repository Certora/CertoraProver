methods {
    function ghostUpdater(address a, int n) external envfree;
    function ghostUpdaterReverts(address a, int n) external envfree;
    function updateUnsignedMap(address a, uint u) external envfree;
}

ghost mapping(address => int) ghostAddrToInt256;
ghost mapping(uint8 => mapping(bytes4 => mapping(mathint => mapping(address => int8)))) ghostNestedMapping;
persistent ghost mapping(address => int) persistentGhostAddrToInt256;
persistent ghost bool hookCalled;

hook Sstore currentContract._n[KEY address a] int newVal {
    hookCalled = true;
    ghostAddrToInt256[a] = newVal;
    persistentGhostAddrToInt256[a] = newVal;
}

rule basic {
    mathint initialSum = sum address a. ghostAddrToInt256[a];
    address addr;
    int oldVal = ghostAddrToInt256[addr];
    int newVal;
    ghostAddrToInt256[addr] = newVal;
    assert (sum address a. ghostAddrToInt256[a]) == initialSum + newVal - oldVal;

    // Verify we "hook" onto reads of the ghost also when in an assigning command _to_ that ghost.
    ghostAddrToInt256[addr] = require_int256(ghostAddrToInt256[addr] * 2);
    assert (sum address a. ghostAddrToInt256[a]) == initialSum + (newVal * 2) - oldVal;
    satisfy true; // to force creation of calltrace
}

rule nestedMapping {
    uint8 u8;
    mathint m;
    bytes4 b4;
    address addr;

    mathint initialSum1 = sum bytes4 b, address a. ghostNestedMapping[u8][b][m][a];
    mathint initialSum2 = sum uint8 u, address a. ghostNestedMapping[u][b4][m][a];

    int8 oldVal = ghostNestedMapping[u8][b4][m][addr];
    int8 newVal;
    ghostNestedMapping[u8][b4][m][addr] = newVal;

    uint8 differentU;
    require differentU != u8;
    bytes4 differentB;
    require differentB != b4;
    int8 anotherNewVal;
    ghostNestedMapping[differentU][differentB][m][addr] = anotherNewVal; // shouldn't affect the sum below

    assert (sum bytes4 b, address a. ghostNestedMapping[u8][b][m][a]) == initialSum1 + newVal - oldVal;
    assert (sum uint8 u, address a. ghostNestedMapping[u][b4][m][a]) == initialSum2 + newVal - oldVal;
    satisfy true; // to force creation of calltrace
}

rule nestedMappingSumOnAllIndices {
    mathint initialSum = sum uint8 u, bytes4 b, mathint m, address a. ghostNestedMapping[u][b][m][a];

    uint8 u8;
    mathint ma;
    bytes4 b4;
    address addr;

    int8 oldVal = ghostNestedMapping[u8][b4][ma][addr];
    int8 newVal;
    ghostNestedMapping[u8][b4][ma][addr] = newVal;

    assert (sum uint8 u, bytes4 b, mathint m, address a. ghostNestedMapping[u][b][m][a]) == initialSum + newVal - oldVal;
    satisfy true;
}

rule updateViaHook {
    mathint initialSum = sum address a. ghostAddrToInt256[a];
    address addr;
    int oldVal = ghostAddrToInt256[addr];
    int newVal;
    ghostUpdater(addr, newVal);
    assert (sum address a. ghostAddrToInt256[a]) == initialSum + newVal - oldVal;
    satisfy true; // to force creation of calltrace
}

rule havocSumBasic {
    mathint initialSum = sum address a. ghostAddrToInt256[a];
    havoc ghostAddrToInt256;
    satisfy (sum address a. ghostAddrToInt256[a]) != initialSum;
}

rule havocSumNestedMapping {
    uint8 u;
    mathint m;
    mathint initialSum = sum bytes4 b, address a. ghostNestedMapping[u][b][m][a];
    mathint initialBasicGhostSum = sum address a. ghostAddrToInt256[a];
    havoc ghostNestedMapping;
    assert (sum address a. ghostAddrToInt256[a]) == initialBasicGhostSum, "non havoc'ed ghost sum shouldn't havoc";
    satisfy (sum bytes4 b, address a. ghostNestedMapping[u][b][m][a]) != initialSum;
}

rule restoreStateAfterRevert {
    hookCalled = false;
    mathint initialSum = sum address a. ghostAddrToInt256[a];
    mathint initialPersistentSum = sum address a. persistentGhostAddrToInt256[a];
    address addr;
    int oldVal = ghostAddrToInt256[addr];
    int oldPersistentVal = persistentGhostAddrToInt256[addr];
    int newVal;
    require newVal != oldVal;
    ghostUpdaterReverts@withrevert(addr, newVal);

    assert hookCalled;
    assert lastReverted <=> (sum address a. ghostAddrToInt256[a]) == initialSum;
    assert (sum address a. persistentGhostAddrToInt256[a]) == initialPersistentSum + newVal - oldPersistentVal;
}

rule sumInQuant {
    require forall uint8 u. forall mathint m. (sum bytes4 b, address a. ghostNestedMapping[u][b][m][a]) >= 0;
    uint8 u;
    mathint m;
    assert (sum bytes4 b, address a. ghostNestedMapping[u][b][m][a]) >= 0;
}

ghost mapping(address => uint256) unsignedGhost;
hook Sstore currentContract.unsignedMap[KEY address a] uint256 new_value {
    unsignedGhost[a] = new_value;
}

hook Sload uint256 value currentContract.unsignedMap[KEY address a] {
    require unsignedGhost[a] == value;
}
rule unsignedBasic {
    mathint initialSum = usum address a. unsignedGhost[a];
    assert initialSum >= 0;
    address addr;
    mathint oldVal = unsignedGhost[addr];
    assert (usum address a. unsignedGhost[a]) >= oldVal;
    uint256 newVal;
    require newVal >= 0;
    unsignedGhost[addr] = newVal;
    mathint updatedSum = usum address a. unsignedGhost[a];
    assert updatedSum == initialSum + newVal - oldVal;
    assert updatedSum >= 0;
    satisfy true; // to force creation of calltrace
}

ghost mapping(uint8 => mapping(bytes4 => mapping(mathint => mapping(address => uint8)))) unsignedNestedGhost;
rule unsignedNestedMapping {
    uint8 u = 8;
    mathint m;
    mathint initialSum = usum bytes4 b, address a. unsignedNestedGhost[8][b][m][a];
    assert initialSum >= 0;

    bytes4 b4;
    address addr;
    uint8 oldVal = unsignedNestedGhost[u][b4][m][addr];
    assert (usum bytes4 b, address a. unsignedNestedGhost[8][b][m][a]) >= oldVal;
    uint8 newVal;
    unsignedNestedGhost[8][b4][m][addr] = newVal;

    uint8 differentU;
    require differentU != u;
    uint8 anotherNewVal;
    unsignedNestedGhost[differentU][b4][m][addr] = anotherNewVal; // shouldn't affect the sum below

    mathint updatedSum = usum bytes4 b, address a. unsignedNestedGhost[u][b][m][a];
    assert updatedSum == initialSum + newVal - oldVal;
    assert updatedSum >= 0;
    satisfy true; // to force creation of calltrace
}

rule havocUsumBasic {
    mathint initialSum = usum address a. unsignedGhost[a];
    assert initialSum >= 0;
    address addr;
    uint256 u;
    updateUnsignedMap(addr, u);
    havoc unsignedGhost;
    assert (usum address a. unsignedGhost[a]) >= 0;
    satisfy (usum address a. unsignedGhost[a]) != initialSum;
}

rule usumInQuant {
    uint8 u8;
    mathint mi;
    bytes4 b4;
    address addr;
    bytes4 b42;
    address addr2;
    require b4 != b42;
    require forall uint8 u. forall mathint m. (usum bytes4 b, address a. unsignedNestedGhost[u][b][m][a]) <= unsignedNestedGhost[u8][b4][mi][addr] + unsignedNestedGhost[u8][b42][mi][addr2];
    assert (usum bytes4 b, address a. unsignedNestedGhost[u8][b][mi][a]) == unsignedNestedGhost[u8][b4][mi][addr] + unsignedNestedGhost[u8][b42][mi][addr2];
}
