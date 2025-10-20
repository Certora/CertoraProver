rule payloaddd () {
    env e;

    bytes expectedPayload;
    require expectedPayload.length == 153;
    require expectedPayload[0] == to_bytes1(0x00);

    bytes payload = payloaddd(e);

    assert payload[0] == expectedPayload[0], "Assert 0";
}
