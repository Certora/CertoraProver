// SPDX-License-Identifier: UNLICENSED

pragma solidity >=0.8.7 <0.9.0;

contract Test {
    struct S {
        int200 off; // packed together with `tail`: 200 + 56 = 256 bits
        uint56 tail;
    }

    mapping(uint256 => S) public m;

    // The accessors below make StorageTypeBounder see loads/stores of the packed
    // signed field, so it normalizes the split variable to hold sign-extended
    // values (and strips the SIGNEXTEND from the contract's own loads).
    function setOff(uint256 k, int200 v) external {
        m[k].off = v;
    }

    function getOff(uint256 k) external view returns (int200) {
        return m[k].off;
    }

    function setTail(uint256 k, uint56 v) external {
        m[k].tail = v;
    }

    function getTail(uint256 k) external view returns (uint56) {
        return m[k].tail;
    }

    // Same shape with the packing order reversed, so the signed field sits at a
    // non-zero offset (byte 7) within its slot. The split variable for it is
    // still bottom-aligned, so the sign-extended convention applies unchanged.
    struct S2 {
        uint56 head;
        int200 off;
    }

    mapping(uint256 => S2) public m2;

    function setOff2(uint256 k, int200 v) external {
        m2[k].off = v;
    }

    function getOff2(uint256 k) external view returns (int200) {
        return m2[k].off;
    }

    function setHead2(uint256 k, uint56 v) external {
        m2[k].head = v;
    }

    function getHead2(uint256 k) external view returns (uint56) {
        return m2[k].head;
    }
}
