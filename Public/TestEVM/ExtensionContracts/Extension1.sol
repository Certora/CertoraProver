import "Immutable.sol";
import {Base1} from "Base1.sol";

contract Extension1 is Immutable {
    Base1 private immutable base1;
    uint inExtension1;
    uint inExtension2;

    address extension2Address;

    // Function that the base contract will delegate to
    function setInExtension1(uint n) public {
        inExtension1 = n;
    }

    function getInExtension1() public view returns (uint) {
        return inExtension1;
    }

    fallback() external payable {
        // Delegate the call to contract B
        (bool success, ) = extension2Address.delegatecall(msg.data);
        require(success, "Delegatecall failed");
    }

    function getExtensionImmut() public view returns (bytes32) {
        return IMMUT1;
    }

    function callFlipB() public {
        base1.flipB();
    }
}
