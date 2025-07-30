import { Abstract, S as MyS } from "./Abstract.sol";

contract D is Abstract {
    function _noImplementation(MyS memory s) internal override {}

    function _onlyD() internal {}

    function noHarness() public {}
}
