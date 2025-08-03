contract LHarness {
    function getStorage() pure internal returns (mapping (address => uint) storage $) {
        assembly {
            $.slot := 3
        }
    }

    function ownCall() external returns (uint) {
        return 3;
    }

    function doSet() external {
        getStorage()[msg.sender] = this.ownCall();
    }

    function doGet() external returns (uint) {
        return getStorage()[msg.sender];
    }
}

contract LTest {
    mapping(uint => uint) m;

    address delegate;

    function delegateAndRevert(
        uint x
    ) external {
        assembly {
            let get_wrekt := mload(0x40)
            mstore(0x40, caller())
            mstore(0x40, get_wrekt)
        }

        (bool rc, bytes memory z) = delegate.delegatecall(abi.encodeWithSignature("doSet()"));
        require(rc);

        if(x == 0) {
            revert("never mind");
        }
    }

    function test() public returns (uint) {
        // confuse the poor PTA
        try this.delegateAndRevert(0) {

        } catch { }

        return this.delegateAndGet();
    }

    function delegateAndGet() external returns (uint) {
        assembly {
            let get_wrekt := mload(0x40)
            mstore(0x40, caller())
            mstore(0x40, get_wrekt)
        }

        (bool rc, bytes memory buff) = delegate.delegatecall(abi.encodeWithSignature("doGet()"));
        require(rc);
        return abi.decode(buff, (uint));
    }
}
