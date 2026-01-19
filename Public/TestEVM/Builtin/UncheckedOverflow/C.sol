contract C {

    function basicMul(uint x, uint y) public pure returns (uint)  {
        unchecked {
            return x * y;
        }
    }

    function byConst(uint x) public pure returns (uint) {
        unchecked {
            return x * 7;
        }
    }

    function mixed(uint x, uint y) public pure returns (int)  {
        unchecked {
            return int256(uint128(y) * x) + 7;
        }
    }

    function twoMuls(uint x, uint y) public pure returns (uint)  {
        unchecked {
            return x * y * x;
        }
    }

    function shouldPass(uint x, uint y)  public pure returns (uint)  {
        require(x < 1000 && y < 1000);
        unchecked {
            return x * y;
        }
    }

    function minus_fail(uint x, uint y) public pure returns (uint)  {
        unchecked {
            return x - y;
        }
    }

    function minus_pass(uint x, uint y) public pure returns (uint)  {
        require(x >= y);
        unchecked {
            return x - y;
        }
    }
}