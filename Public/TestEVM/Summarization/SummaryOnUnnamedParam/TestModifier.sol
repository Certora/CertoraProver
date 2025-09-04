import { TestModifierParent } from "./TestModifierParent.sol";

contract TestModifier is TestModifierParent {

    bool not42;

    function internalUnimplementedFunctionWithUnnamedParam(uint256) internal onlyOwner {}

    function setBool(uint x) internal {
        if(x != 42) {
            not42 = true;
        }
    }

    function test(uint x) external {
        uint y = x;
        setBool(x);
        internalUnimplementedFunctionWithUnnamedParam(y);
    }
}
