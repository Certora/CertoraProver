contract Dummy {
    bool public b;
    bool public c;
    uint256 public x;
    uint256 public y;
    uint256 public z;
    bool foo_selected;

    function foo() external {
        b = (x*x*x + y*y*y != z*z*z) || (x*y*z == 0);
    }
}
