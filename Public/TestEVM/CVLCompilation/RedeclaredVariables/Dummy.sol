pragma solidity 0.8.21;

import {C} from "./inner/C.sol";
import {I} from "./inner/I.sol"; // import path relative to sol file

contract Dummy {

    mapping(I.E => address) internal _total;

    function func(C c) external returns(C) {
        return c;
    }

}
