pragma solidity 0.8.24;

import "./SomeInterface.sol";

contract StrongInvariantsParam {
    struct Foo {
        uint val;
        Bar bar;
    }
    struct Bar {
        uint valBar;
    }
    uint256 storageValue = 1;
    function callUnresolveExternal(SomeInterface token) external {
        storageValue = 1;
        //There is an unresolved external call, so the invariant storageValue === 1 should be checked here and shouldn't fail
        address(token).delegatecall(abi.encodeWithSignature("havocAllContracts()"));
        storageValue = 1;
    }

    function trivialReturn(uint param) external returns (uint){
        if(storageValue == 1){
            return param;
        } else{
            return storageValue;
        }
    }

    function trivialUse(uint[] calldata y) external returns (uint[] calldata){
        return y;
    }

    function trivialUse(uint[][] calldata y) external returns (uint[][] calldata){
        return y;
    }

    function trivialUse(bytes calldata y) external returns (bytes calldata){
        return y;
    }

    function trivialUse(Foo calldata y) external returns (Foo calldata){
        return y;
    }
}
