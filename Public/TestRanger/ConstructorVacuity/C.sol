contract C {
    uint n;
    constructor(uint _n) {
        require(_n > 0);
        n = _n;
    }
}
