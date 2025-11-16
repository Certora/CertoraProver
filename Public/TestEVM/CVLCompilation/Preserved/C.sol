contract C {
    uint x;
    uint timestamp;
    constructor(uint _x) {
        x = _x;
        timestamp = block.timestamp;
    }
    function foo(uint a) public {
        x = a;
        timestamp = block.timestamp;
    }
}

contract D {
    uint public initialized_at;
}
