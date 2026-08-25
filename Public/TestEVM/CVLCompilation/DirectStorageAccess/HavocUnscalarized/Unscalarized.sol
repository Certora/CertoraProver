// SPDX-License-Identifier: UNLICENSED

pragma solidity >=0.8.7 <0.9.0;

contract Unscalarized {
    struct S {
        uint128 lo;
        uint128 hi;
    }

    struct T {
        int128 lo;
        int128 hi;
    }

    // b is neither at the start nor at the end of its slot
    struct U {
        uint64 a;
        uint128 b;
        uint64 c;
    }

    S s;
    mapping(uint256 => S) s_mapping;

    T t;

    U u;

    // Reading whole slots from assembly prevents the packed slots from being scalarized,
    // so accesses to their fields go through the masking path of the storage access compiler.
    function splat() public view returns (uint256 ret) {
        uint256 tmp1;
        uint256 tmp2;
        uint256 tmp3;
        assembly {
            tmp1 := sload(s.slot)
            tmp2 := sload(t.slot)
            tmp3 := sload(u.slot)
        }
        ret = tmp1 + tmp2 + tmp3;
    }

    function splat_map(uint256 i) public view returns (uint256 ret) {
        S storage p = s_mapping[i];
        assembly {
            ret := sload(p.slot)
        }
    }
}
