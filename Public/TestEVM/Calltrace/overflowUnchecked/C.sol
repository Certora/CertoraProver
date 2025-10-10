contract C {

    function mul(uint a, uint b) public returns (uint)  {
        unchecked {
            return a * b;
        }
    }

}