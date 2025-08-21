contract C {
    mapping(uint => uint) m;

    function setM(uint i, uint v) public  {
        m[i] = v;
    }
}