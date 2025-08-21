// SPDX-License-Identifier: MIT
pragma solidity 0.8.21;

import {I} from "inner/I.sol"; // import path relative to the project root

contract C {

    I.S public s;

    function func(I.E e) public returns (I.E,I.E) {
        return (e,e);
    }
}
