import "./Bar.sol";

contract Contract {
    struct Point {
        uint x;
        uint y;
    }

    uint storageResult;
    Bar bar;

    function addToStorage(uint a) public returns (uint) {
        // Intentional bug to make the rule fail
        storageResult = storageResult - a;
        return storageResult;
    }
    function add(uint a, uint b) public pure returns (uint) {
        // Intentional bug to make the rule fail
        return a - b;
    }

    function sumCoordinates(Point memory t) public pure returns (uint) {
        return add(t.x, t.y);
    }

    function sumCoordinatesInternal(Point memory t) public pure returns (uint) {
        return _add(t.x, t.y);
    }
    function addSummarizedByNondet(uint a, uint b) public pure returns (uint) {
        return _addSummarizedByNondet(a, b);
    }

    function addSummarizedByCVLFunction(uint a, uint b) public returns (uint) {
        _addSummmarizedByCVLFunction(a, b);
        return storageResult;
    }
    function _add(uint a, uint b) internal pure returns (uint) {
        // Intentional bug to make the rule fail
        return a - b;
    }

    function _addSummmarizedByCVLFunction(uint a, uint b) internal {
        // This call will be summarized
        storageResult = a + b;
    }

    function _addSummarizedByNondet(
        uint a,
        uint b
    ) internal pure returns (uint) {
        // This call will be summarized
        return a + b;
    }

    function addInExternalCall(uint a, uint b) public returns (uint) {
        return bar.add(a, b);
    }

    function severalSolidityCalls(uint a, uint b) public returns (uint) {
        uint z = add(a, b);
        uint w = add(foo(z), foo(z));
        return w;
    }

    function foo(uint foo_a) public returns (uint) {
        uint x = baz(foo_a) + 1;
        x = baz(x) + 1;
        x = baz(x) + 1;
        return x;
    }

    function baz(uint bar_a) internal returns (uint) {
        return bar_a + 1;
    }

    function unusedParameter(uint bar_a, uint foo) public returns (uint) {
        return bar_a + 1;
    }
}
