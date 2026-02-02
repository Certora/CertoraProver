contract Test {
    address impl;
    function doThing(uint[] memory, address) external returns (uint a)  {
        address toCall = impl;
        assembly {
            let fp := mload(0x40)
            calldatacopy(fp, 0, calldatasize())
            let res := delegatecall(gas(), toCall, fp, calldatasize(), 0, 0)

            returndatacopy(fp, 0, returndatasize())

            switch res
            case 0 { revert(fp, returndatasize()) }
            default { return(fp, returndatasize()) }
        }
    }
}