

function interpretCoordinatesCVL(Test.Complex[] target, uint[4] fields) returns uint {
    uint complexIdx = fields[0];
    require(complexIdx < target.length, "selected index within bounds");

    uint topLevel = fields[1];
    if(topLevel == 0) {
        // myStaticField
        require(fields[2] < 4, "static index within bounds");
        return target[complexIdx].myStaticField[fields[2]];
    } else if(topLevel == 1) {
        // myBytes, can't do this one yet :(
        require(false, "not actually supported");
    } else if(topLevel == 2) {
        // myArray
        uint isLength = fields[2];
        uint theLength = target[complexIdx].myArray.length;
        if(isLength == 1) {
            return theLength;
        } else {
            uint idx = fields[3];
            require(idx < theLength, "index within bounds");
            return target[complexIdx].myArray[idx];
        }
    } else if(topLevel == 3) {
        // myPrimitiveField
        return target[complexIdx].myPrimitiveField;
    } else if(topLevel == 4) {
        // myStaticStruct
        uint childField = fields[2];
        if(childField == 0) {
            return target[complexIdx].myStaticStruct.myOtherPrimitive;
        } else if(childField == 1) {
            uint prophecy;
            require(to_bytes32(prophecy) == target[complexIdx].myStaticStruct.myBytesK, "bind");
            return prophecy;
        } else {
            require(false, "not a valid sub-field coord");
        }
    } else {
        require(false, "not a valid field coord");
    }
    return 0; // dummy
}


function interpretCoordinatesStorage(uint target, address who, uint[4] fields) returns uint {
    uint complexIdx = fields[0];
    require(complexIdx < currentContract.myComplexDataStructure[target][who].length, "selected index within bounds");

    uint topLevel = fields[1];
    if(topLevel == 0) {
        // myStaticField
        require(fields[2] < 4, "static index within bounds");
        return currentContract.myComplexDataStructure[target][who][complexIdx].myStaticField[fields[2]];
    } else if(topLevel == 1) {
        // myBytes, can't do this one yet :(
        require(false, "not actually supported");
    } else if(topLevel == 2) {
        // myArray
        uint isLength = fields[2];
        uint theLength = currentContract.myComplexDataStructure[target][who][complexIdx].myArray.length;
        if(isLength == 1) {
            return theLength;
        } else {
            uint idx = fields[3];
            require(idx < theLength, "index within bounds");
            return currentContract.myComplexDataStructure[target][who][complexIdx].myArray[idx];
        }
    } else if(topLevel == 3) {
        // myPrimitiveField
        return currentContract.myComplexDataStructure[target][who][complexIdx].myPrimitiveField;
    } else if(topLevel == 4) {
        // myStaticStruct
        uint childField = fields[2];
        if(childField == 0) {
            return currentContract.myComplexDataStructure[target][who][complexIdx].myStaticStruct.myOtherPrimitive;
        } else if(childField == 1) {
            uint prophecy;
            require(to_bytes32(prophecy) == currentContract.myComplexDataStructure[target][who][complexIdx].myStaticStruct.myBytesK, "bind");
            return prophecy;
        } else {
            require(false, "not a valid sub-field coord");
        }
    } else {
        require(false, "not a valid field coord");
    }
    return 0; // dummy
}

methods {
    function Test.doInternalUpdate(
        mapping(address => Test.Complex[]) storage r,
        address who,
        Test.Complex[] memory m
    ) internal returns (Test.Complex[] memory) => MyHarnessLibrary.rerouteTarget(r, who, m);

    function Test.doOtherUpdate(
        mapping(address => Test.Simpler[]) storage r,
        address who,
        Test.Simpler[] memory m
    ) internal returns (Test.Simpler[] memory) => MyHarnessLibrary.simpler(r, who, m);
}

rule unified_test(env e) {
    Test.Complex[] arg;
    uint[4] fieldCoord;
    uint target;
    uint expectedNewValue = interpretCoordinatesCVL(arg, fieldCoord);

    uint expectedCurr = interpretCoordinatesStorage(target, e.msg.sender, fieldCoord);

    Test.Complex[] returned = externalEntry(e, target, arg);
    assert interpretCoordinatesCVL(returned, fieldCoord) == expectedCurr;

    assert interpretCoordinatesStorage(target, e.msg.sender, fieldCoord) == expectedNewValue;
}

rule easier_test(env e) {
    Test.Simpler[] arg;
    uint target;
    uint i;
    uint j;
    require(i < arg.length, "in bounds");
    require(i < currentContract.simplerDataStructure[target][e.msg.sender].length, "in bounds");
    require(j < arg[i].data.length, "in bounds");
    require(j < currentContract.simplerDataStructure[target][e.msg.sender][i].data.length, "in bounds");

    uint expectedRet = currentContract.simplerDataStructure[target][e.msg.sender][i].data[j];

    Test.Simpler[] returned = externalEntry2(e, target, arg);
    assert returned[i].data[j] == expectedRet;
    assert currentContract.simplerDataStructure[target][e.msg.sender][i].data[j] == arg[i].data[j];
}

rule easier_negative_test(env e) {
    Test.Simpler[] arg;
    uint target;
    uint i;
    uint j;
    require(i < arg.length, "in bounds");
    require(i < currentContract.simplerDataStructure[target][e.msg.sender].length, "in bounds");
    require(j < arg[i].data.length, "in bounds");
    require(j < currentContract.simplerDataStructure[target][e.msg.sender][i].data.length, "in bounds");

    uint expectedRet = currentContract.simplerDataStructure[target][e.msg.sender][i].data[j];

    Test.Simpler[] returned = externalEntry2(e, target, arg);
	// this is what we'd expect if the summary didn't apply
    assert returned.length == 0;
    assert currentContract.simplerDataStructure[target][e.msg.sender].length == 0;
}
