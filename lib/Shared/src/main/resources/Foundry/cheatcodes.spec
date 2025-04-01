methods {
    // Assertion cheatcodes
    function _.assertTrue(bool condition) internal => assertTrueSumm(condition) expect void;
    function _.assertTrue(bool condition, string memory err) internal => assertTrueSumm(condition) expect void;

    function _.assertFalse(bool condition) internal => assertFalseSumm(condition) expect void;
    function _.assertFalse(bool condition, string memory err) internal => assertFalseSumm(condition) expect void;

    function _.assertEq(bool a, bool b) internal => assertEqBoolSumm(a, b) expect void;
    function _.assertEq(bool a, bool b, string memory err) internal => assertEqBoolSumm(a, b) expect void;
    function _.assertEq(uint256 a, uint256 b) internal => assertEqSumm(a, b) expect void;
    function _.assertEq(uint256 a, uint256 b, string memory err) internal => assertEqSumm(a, b) expect void;
    function _.assertEq(int256 a, int256 b) internal => assertEqSumm(a, b) expect void;
    function _.assertEq(int256 a, int256 b, string memory err) internal => assertEqSumm(a, b) expect void;
    function _.assertEq(address a, address b) internal => assertEqAddressSumm(a, b) expect void;
    function _.assertEq(address a, address b, string memory err) internal => assertEqAddressSumm(a, b) expect void;
    function _.assertEq(bytes32 a, bytes32 b) internal => assertEqBytes32Summ(a, b) expect void;
    function _.assertEq(bytes32 a, bytes32 b, string memory err) internal => assertEqBytes32Summ(a, b) expect void;
    function _.assertEq(string memory a, string memory b) internal => assertEqStringSumm(a, b) expect void;
    function _.assertEq(string memory a, string memory b, string memory err) internal => assertEqStringSumm(a, b) expect void;
    function _.assertEq(bytes memory a, bytes memory b) internal => assertEqBytesSumm(a, b) expect void;
    function _.assertEq(bytes memory a, bytes memory b, string memory err) internal => assertEqBytesSumm(a, b) expect void;
    function _.assertEq(bool[] memory a, bool[] memory b) internal => assertEqBoolArrSumm(a, b) expect void;
    function _.assertEq(bool[] memory a, bool[] memory b, string memory err) internal => assertEqBoolArrSumm(a, b) expect void;
    function _.assertEq(uint256[] memory a, uint256[] memory b) internal => assertEqUintArrSumm(a, b) expect void;
    function _.assertEq(uint256[] memory a, uint256[] memory b, string memory err) internal => assertEqUintArrSumm(a, b) expect void;
    function _.assertEq(int256[] memory a, int256[] memory b) internal => assertEqIntArrSumm(a, b) expect void;
    function _.assertEq(int256[] memory a, int256[] memory b, string memory err) internal => assertEqIntArrSumm(a, b) expect void;
    function _.assertEq(address[] memory a, address[] memory b) internal => assertEqAddressArrSumm(a, b) expect void;
    function _.assertEq(address[] memory a, address[] memory b, string memory err) internal => assertEqAddressArrSumm(a, b) expect void;
    function _.assertEq(bytes32[] memory a, bytes32[] memory b) internal => assertEqBytes32ArrSumm(a, b) expect void;
    function _.assertEq(bytes32[] memory a, bytes32[] memory b, string memory err) internal => assertEqBytes32ArrSumm(a, b) expect void;
    function _.assertEq(string[] memory a, string[] memory b) internal => assertEqStringArrSumm(a, b) expect void;
    function _.assertEq(string[] memory a, string[] memory b, string memory err) internal => assertEqStringArrSumm(a, b) expect void;
    function _.assertEq(bytes[] memory a, bytes[] memory b) internal => assertEqBytesArrSumm(a, b) expect void;
    function _.assertEq(bytes[] memory a, bytes[] memory b, string memory err) internal => assertEqBytesArrSumm(a, b) expect void;

    function _.assertEqDecimal(uint256 a, uint256 b, uint256 decimals) internal => assertEqSumm(a, b) expect void;
    function _.assertEqDecimal(uint256 a, uint256 b, uint256 decimals, string memory err) internal => assertEqSumm(a, b) expect void;
    function _.assertEqDecimal(int256 a, int256 b, uint256 decimals) internal => assertEqSumm(a, b) expect void;
    function _.assertEqDecimal(int256 a, int256 b, uint256 decimals, string memory err) internal => assertEqSumm(a, b) expect void;

    function _.assertNotEq(bool a, bool b) internal => assertNotEqBoolSumm(a, b) expect void;
    function _.assertNotEq(bool a, bool b, string memory err) internal => assertNotEqBoolSumm(a, b) expect void;
    function _.assertNotEq(uint256 a, uint256 b) internal => assertNotEqSumm(a, b) expect void;
    function _.assertNotEq(uint256 a, uint256 b, string memory err) internal => assertNotEqSumm(a, b) expect void;
    function _.assertNotEq(int256 a, int256 b) internal => assertNotEqSumm(a, b) expect void;
    function _.assertNotEq(int256 a, int256 b, string memory err) internal => assertNotEqSumm(a, b) expect void;
    function _.assertNotEq(address a, address b) internal => assertNotEqAddressSumm(a, b) expect void;
    function _.assertNotEq(address a, address b, string memory err) internal => assertNotEqAddressSumm(a, b) expect void;
    function _.assertNotEq(bytes32 a, bytes32 b) internal => assertNotEqBytes32Summ(a, b) expect void;
    function _.assertNotEq(bytes32 a, bytes32 b, string memory err) internal => assertNotEqBytes32Summ(a, b) expect void;
    function _.assertNotEq(string memory a, string memory b) internal => assertNotEqStringSumm(a, b) expect void;
    function _.assertNotEq(string memory a, string memory b, string memory err) internal => assertNotEqStringSumm(a, b) expect void;
    function _.assertNotEq(bytes memory a, bytes memory b) internal => assertNotEqBytesSumm(a, b) expect void;
    function _.assertNotEq(bytes memory a, bytes memory b, string memory err) internal => assertNotEqBytesSumm(a, b) expect void;
    function _.assertNotEq(bool[] memory a, bool[] memory b) internal => assertNotEqBoolArrSumm(a, b) expect void;
    function _.assertNotEq(bool[] memory a, bool[] memory b, string memory err) internal => assertNotEqBoolArrSumm(a, b) expect void;
    function _.assertNotEq(uint256[] memory a, uint256[] memory b) internal => assertNotEqUintArrSumm(a, b) expect void;
    function _.assertNotEq(uint256[] memory a, uint256[] memory b, string memory err) internal => assertNotEqUintArrSumm(a, b) expect void;
    function _.assertNotEq(int256[] memory a, int256[] memory b) internal => assertNotEqIntArrSumm(a, b) expect void;
    function _.assertNotEq(int256[] memory a, int256[] memory b, string memory err) internal => assertNotEqIntArrSumm(a, b) expect void;
    function _.assertNotEq(address[] memory a, address[] memory b) internal => assertNotEqAddressArrSumm(a, b) expect void;
    function _.assertNotEq(address[] memory a, address[] memory b, string memory err) internal => assertNotEqAddressArrSumm(a, b) expect void;
    function _.assertNotEq(bytes32[] memory a, bytes32[] memory b) internal => assertNotEqBytes32ArrSumm(a, b) expect void;
    function _.assertNotEq(bytes32[] memory a, bytes32[] memory b, string memory err) internal => assertNotEqBytes32ArrSumm(a, b) expect void;
    function _.assertNotEq(string[] memory a, string[] memory b) internal => assertNotEqStringArrSumm(a, b) expect void;
    function _.assertNotEq(string[] memory a, string[] memory b, string memory err) internal => assertNotEqStringArrSumm(a, b) expect void;
    function _.assertNotEq(bytes[] memory a, bytes[] memory b) internal => assertNotEqBytesArrSumm(a, b) expect void;
    function _.assertNotEq(bytes[] memory a, bytes[] memory b, string memory err) internal => assertNotEqBytesArrSumm(a, b) expect void;

    function _.assertNotEqDecimal(uint256 a, uint256 b, uint256 decimals) internal => assertNotEqSumm(a, b) expect void;
    function _.assertNotEqDecimal(uint256 a, uint256 b, uint256 decimals, string memory err) internal => assertNotEqSumm(a, b) expect void;
    function _.assertNotEqDecimal(int256 a, int256 b, uint256 decimals) internal => assertNotEqSumm(a, b) expect void;
    function _.assertNotEqDecimal(int256 a, int256 b, uint256 decimals, string memory err) internal => assertNotEqSumm(a, b) expect void;

    function _.assertLt(uint256 a, uint256 b) internal => assertLtSumm(a, b) expect void;
    function _.assertLt(uint256 a, uint256 b, string memory err) internal => assertLtSumm(a, b) expect void;
    function _.assertLt(int256 a, int256 b) internal => assertLtSumm(a, b) expect void;
    function _.assertLt(int256 a, int256 b, string memory err) internal => assertLtSumm(a, b) expect void;
    function _.assertLtDecimal(uint256 a, uint256 b, uint256 decimals) internal => assertLtSumm(a, b) expect void;
    function _.assertLtDecimal(uint256 a, uint256 b, uint256 decimals, string memory err) internal => assertLtSumm(a, b) expect void;
    function _.assertLtDecimal(int256 a, int256 b, uint256 decimals) internal => assertLtSumm(a, b) expect void;
    function _.assertLtDecimal(int256 a, int256 b, uint256 decimals, string memory err) internal => assertLtSumm(a, b) expect void;

    function _.assertGt(uint256 a, uint256 b) internal => assertGtSumm(a, b) expect void;
    function _.assertGt(uint256 a, uint256 b, string memory err) internal => assertGtSumm(a, b) expect void;
    function _.assertGt(int256 a, int256 b) internal => assertGtSumm(a, b) expect void;
    function _.assertGt(int256 a, int256 b, string memory err) internal => assertGtSumm(a, b) expect void;
    function _.assertGtDecimal(uint256 a, uint256 b, uint256 decimals) internal => assertGtSumm(a, b) expect void;
    function _.assertGtDecimal(uint256 a, uint256 b, uint256 decimals, string memory err) internal => assertGtSumm(a, b) expect void;
    function _.assertGtDecimal(int256 a, int256 b, uint256 decimals) internal => assertGtSumm(a, b) expect void;
    function _.assertGtDecimal(int256 a, int256 b, uint256 decimals, string memory err) internal => assertGtSumm(a, b) expect void;

    function _.assertLe(uint256 a, uint256 b) internal => assertLeSumm(a, b) expect void;
    function _.assertLe(uint256 a, uint256 b, string memory err) internal => assertLeSumm(a, b) expect void;
    function _.assertLe(int256 a, int256 b) internal => assertLeSumm(a, b) expect void;
    function _.assertLe(int256 a, int256 b, string memory err) internal => assertLeSumm(a, b) expect void;
    function _.assertLeDecimal(uint256 a, uint256 b, uint256 decimals) internal => assertLeSumm(a, b) expect void;
    function _.assertLeDecimal(uint256 a, uint256 b, uint256 decimals, string memory err) internal => assertLeSumm(a, b) expect void;
    function _.assertLeDecimal(int256 a, int256 b, uint256 decimals) internal => assertLeSumm(a, b) expect void;
    function _.assertLeDecimal(int256 a, int256 b, uint256 decimals, string memory err) internal => assertLeSumm(a, b) expect void;

    function _.assertGe(uint256 a, uint256 b) internal => assertGeSumm(a, b) expect void;
    function _.assertGe(uint256 a, uint256 b, string memory err) internal => assertGeSumm(a, b) expect void;
    function _.assertGe(int256 a, int256 b) internal => assertGeSumm(a, b) expect void;
    function _.assertGe(int256 a, int256 b, string memory err) internal => assertGeSumm(a, b) expect void;
    function _.assertGeDecimal(uint256 a, uint256 b, uint256 decimals) internal => assertGeSumm(a, b) expect void;
    function _.assertGeDecimal(uint256 a, uint256 b, uint256 decimals, string memory err) internal => assertGeSumm(a, b) expect void;
    function _.assertGeDecimal(int256 a, int256 b, uint256 decimals) internal => assertGeSumm(a, b) expect void;
    function _.assertGeDecimal(int256 a, int256 b, uint256 decimals, string memory err) internal => assertGeSumm(a, b) expect void;

    function _.assertApproxEqAbs(uint256 a, uint256 b, uint256 maxDelta) internal => assertApproxEqAbsSumm(a, b, maxDelta) expect void;
    function _.assertApproxEqAbs(uint256 a, uint256 b, uint256 maxDelta, string memory err) internal => assertApproxEqAbsSumm(a, b, maxDelta) expect void;
    function _.assertApproxEqAbs(int256 a, int256 b, uint256 maxDelta) internal => assertApproxEqAbsSumm(a, b, maxDelta) expect void;
    function _.assertApproxEqAbs(int256 a, int256 b, uint256 maxDelta, string memory err) internal => assertApproxEqAbsSumm(a, b, maxDelta) expect void;
    function _.assertApproxEqAbsDecimal(uint256 a, uint256 b, uint256 maxDelta, uint256 decimals) internal => assertApproxEqAbsSumm(a, b, maxDelta) expect void;
    function _.assertApproxEqAbsDecimal(uint256 a, uint256 b, uint256 maxDelta, uint256 decimals, string memory err) internal => assertApproxEqAbsSumm(a, b, maxDelta) expect void;
    function _.assertApproxEqAbsDecimal(int256 a, int256 b, uint256 maxDelta, uint256 decimals) internal => assertApproxEqAbsSumm(a, b, maxDelta) expect void;
    function _.assertApproxEqAbsDecimal(int256 a, int256 b, uint256 maxDelta, uint256 decimals, string memory err) internal => assertApproxEqAbsSumm(a, b, maxDelta) expect void;

    function _.assertApproxEqRel(uint256 a, uint256 b, uint256 maxPercentDelta, string memory err) internal => assertApproxEqRelSumm(a, b, maxPercentDelta) expect void;
    function _.assertApproxEqRel(uint256 a, uint256 b, uint256 maxPercentDelta) internal => assertApproxEqRelSumm(a, b, maxPercentDelta) expect void;
    function _.assertApproxEqRel(int256 a, int256 b, uint256 maxPercentDelta) internal => assertApproxEqRelSumm(a, b, maxPercentDelta) expect void;
    function _.assertApproxEqRel(int256 a, int256 b, uint256 maxPercentDelta, string memory err) internal => assertApproxEqRelSumm(a, b, maxPercentDelta) expect void;
    function _.assertApproxEqRelDecimal(uint256 a, uint256 b, uint256 maxPercentDelta, uint256 decimals) internal => assertApproxEqRelSumm(a, b, maxPercentDelta) expect void;
    function _.assertApproxEqRelDecimal(uint256 a, uint256 b, uint256 maxPercentDelta, uint256 decimals, string memory err) internal => assertApproxEqRelSumm(a, b, maxPercentDelta) expect void;
    function _.assertApproxEqRelDecimal(int256 a, int256 b, uint256 maxPercentDelta, uint256 decimals) internal => assertApproxEqRelSumm(a, b, maxPercentDelta) expect void;
    function _.assertApproxEqRelDecimal(int256 a, int256 b, uint256 maxPercentDelta, uint256 decimals, string memory err) internal => assertApproxEqRelSumm(a, b, maxPercentDelta) expect void;
}

function assertTrueSumm(bool condition) { assert condition, "assertTrue failed"; }

function assertFalseSumm(bool condition) { assert !condition, "assertFalse failed"; }


function assertEqBoolSumm(bool a, bool b) { assert a == b; }

function assertEqSumm(mathint a, mathint b) { assert a == b; }

function assertEqAddressSumm(address a, address b) { assert a == b; }

function assertEqBytes32Summ(bytes32 a, bytes32 b) { assert a == b; }

function assertEqStringSumm(string a, string b) { assert a == b; }

function assertEqBytesSumm(bytes a, bytes b) { assert a == b; }

function assertEqBoolArrSumm(bool[] a, bool[] b) { assert a.length == b.length && (forall uint i. i < a.length => a[i] == b[i]); }

function assertEqUintArrSumm(uint[] a, uint[] b) { assert a.length == b.length && (forall uint i. i < a.length => a[i] == b[i]); }

function assertEqIntArrSumm(int[] a, int[] b) { assert a.length == b.length && (forall uint i. i < a.length => a[i] == b[i]); }

function assertEqAddressArrSumm(address[] a, address[] b) { assert a.length == b.length && (forall uint i. i < a.length => a[i] == b[i]); }

function assertEqBytes32ArrSumm(bytes32[] a, bytes32[] b) { assert a.length == b.length && (forall uint i. i < a.length => a[i] == b[i]); }

function assertEqStringArrSumm(string[] a, string[] b) { assert a.length == b.length && (forall uint i. i < a.length => a[i] == b[i]); }

function assertEqBytesArrSumm(bytes[] a, bytes[] b) { assert a.length == b.length && (forall uint i. i < a.length => a[i] == b[i]); }


function assertNotEqBoolSumm(bool a, bool b) { assert a != b; }

function assertNotEqSumm(mathint a, mathint b) { assert a != b; }

function assertNotEqAddressSumm(address a, address b) { assert a != b; }

function assertNotEqBytes32Summ(bytes32 a, bytes32 b) { assert a != b; }

function assertNotEqStringSumm(string a, string b) { assert a != b; }

function assertNotEqBytesSumm(bytes a, bytes b) { assert a != b; }

function assertNotEqBoolArrSumm(bool[] a, bool[] b) { assert a.length != b.length || (exists uint i. i < a.length => a[i] != b[i]); }

function assertNotEqUintArrSumm(uint[] a, uint[] b) { assert a.length != b.length || (exists uint i. i < a.length => a[i] != b[i]); }

function assertNotEqIntArrSumm(int[] a, int[] b) { assert a.length != b.length || (exists uint i. i < a.length => a[i] != b[i]); }

function assertNotEqAddressArrSumm(address[] a, address[] b) { assert a.length != b.length || (exists uint i. i < a.length => a[i] != b[i]); }

function assertNotEqBytes32ArrSumm(bytes32[] a, bytes32[] b) { assert a.length != b.length || (exists uint i. i < a.length => a[i] != b[i]); }

function assertNotEqStringArrSumm(string[] a, string[] b) { assert a.length != b.length || (exists uint i. i < a.length => a[i] != b[i]); }

function assertNotEqBytesArrSumm(bytes[] a, bytes[] b) { assert a.length != b.length || (exists uint i. i < a.length => a[i] != b[i]); }


function assertLtSumm(mathint a, mathint b) { assert a < b; }


function assertGtSumm(mathint a, mathint b) { assert a > b; }


function assertLeSumm(mathint a, mathint b) { assert a <= b; }


function assertGeSumm(mathint a, mathint b) { assert a >= b; }


function delta(mathint a, mathint b) returns mathint {
    return a > b ? a - b : b - a;
}

function percentDelta(mathint a, mathint b) returns mathint {
    assert b != 0, "can't calculate percentDelta when `b==0`";
    mathint absDelta = delta(a, b);

    return absDelta * 10^18 / b;
}

function assertApproxEqAbsSumm(mathint a, mathint b, uint256 maxDelta) {
    mathint delta = delta(a, b);
    assert delta <= to_mathint(maxDelta);
}

function assertApproxEqRelSumm(mathint a, mathint b, uint256 maxPercentDelta /* An 18 decimal fixed point number, where 1e18 == 100% */) {
    if (b == 0) {
        assert a == 0;
        return;
    }
    mathint delta = percentDelta(a, b);
    assert delta <= to_mathint(maxPercentDelta);
}

methods {
    // implemented cheatcodes
    function _.prank(address msgSender) external => __certora_foundry_prank(msgSender) expect void;
    function _.startPrank(address msgSender) external => __certora_foundry_startPrank(msgSender) expect void;
    function _.stopPrank() external => __certora_foundry_stopPrank() expect void;
    function _.warp(uint256 newTimestamp) external => __certora_foundry_warp(newTimestamp) expect void;
    function _.mockCall(address where, bytes data, bytes retdata) external => __certora_foundry_mockCall(where, -1, data, retdata) expect void;
    function _.mockCall(address where, uint256 value, bytes data, bytes retdata) external => __certora_foundry_mockCall(where, value, data, retdata) expect void;
    function _.clearMockedCalls() external => __certora_foundry_clearMockedCalls() expect void;
    function _.deal(address to, uint256 give) external => cvlDeal(to, give) expect void;
    function _.deal(address token, address to, uint256 give, bool adjust) internal with (env e) => cvlDealERC20(e, token, to, give, adjust) expect void;
    function _.addr(uint256 privateKey) external => addrSumm(privateKey) expect address;
    function _.makeAddr(string memory name) internal => makeAddrSumm(name) expect address;
    function _.makeAddrAndKey(string memory name) internal => makeAddrAndKeySumm(name) expect (address, uint256);
    function _.sign(uint256 privateKey, bytes32 digest) external => signSumm(privateKey, digest) expect (uint8, bytes32, bytes32);
    function _.assume(bool condition) external => assumeSumm(condition) expect void;
    function _.expectRevert() external => expectRevertSumm() expect void;
    function _.expectRevert(bytes4 revertData) external => expectRevertSumm() expect void;
    function _.expectRevert(bytes revertData) external => expectRevertSumm() expect void;
}

function cvlDeal(address to, uint256 give) {
    require nativeBalances[to] == give;
}

function cvlDealERC20(env e, address token, address to, uint256 give, bool adjust) {
    require token.balanceOf(e, to) == give;

    assert !adjust, "deal cheatcode's 'adjust' not implemented yet";
}

persistent ghost mapping(string => uint256) g_name_to_pk;
persistent ghost mapping(uint256 => address) g_pk_to_addr {
    axiom forall uint256 u. g_pk_to_addr[u] != 0;
}

function addrSumm(uint256 privateKey) returns address {
    return g_pk_to_addr[privateKey];
}

function makeAddrSumm(string name) returns address {
    uint256 pk = g_name_to_pk[name];
    return g_pk_to_addr[pk];
}

function makeAddrAndKeySumm(string name) returns (address, uint256) {
    uint256 pk = g_name_to_pk[name];
    address addr = g_pk_to_addr[pk];
    return (addr, pk);
}

function signSumm(uint256 privateKey, bytes32 digest) returns (uint8, bytes32, bytes32) {
    uint8 v; bytes32 r; bytes32 s;

    require ecrecover(digest, v, r, s) == g_pk_to_addr[privateKey];

    return (v, r, s);
}

persistent ghost bool g_expectRevertAllowed;
persistent ghost bool g_expectRevert;
function expectRevertSumm() {
    if (!g_expectRevertAllowed) {
        assert false, "non reverting test shouldn't call `expectRevert`";
    }
    g_expectRevert = true;
}

function assumeSumm(bool condition) {
    require condition;
}

methods {
    // unimplemented cheatcodes that we can ignore for now
    function _.startBroadcast(address) external => NONDET DELETE;
    function _.stopBroadcast() external => NONDET DELETE;
    function _._sendLogPayload(bytes memory payload) internal => NONDET;
    function _.console2_log_StdUtils(string memory p0) internal => NONDET;
    function _.toString(int256) external => NONDET;
    function _.log(string memory p0, address p1) internal => NONDET;
    function _.label(address account, string newLabel) external => NONDET DELETE;
    function _.rpcUrl(string rpcAlias) external => NONDET DELETE;
    function _.createSelectFork(string urlOrAlias, uint256 blockNumber) external => NONDET DELETE;

    // unimplemented cheatcodes that can't be ignored
    function _.expectCall(address callee, bytes data) external => unsupportedCheatcode() expect void;
    function _.expectCall(address callee, uint256 msgValue, bytes data) external => unsupportedCheatcode() expect void;
}

function unsupportedCheatcode() {
    assert false, "unsupported cheatcode";
}


function init_fuzz_tests(method f, env e) {}
