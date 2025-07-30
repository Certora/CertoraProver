import { Abstract, S as MyS } from "./Abstract.sol";

function freeFunc() pure {} // Here to verify we don't try to create a harness for this - free functions have no visibility modifier

contract C is Abstract {
    function _noImplementation(MyS memory s) internal override {}

    function _withImplementation(address payable a) internal pure override returns (uint256) {
        require(a != address(0));
        return uint256(uint160(address(a)));
    }

    function noHarness() public {}

    function overloaded() external pure  returns (bool) { return true; }
    function overloaded(bool b) internal pure  returns (bool) { return b; }

    function _storageInput(MyS storage s) internal {}
    function _storageOutput() internal view returns (MyS storage s) { return _getHarnessesStorage().s; }
}
