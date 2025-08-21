rule test_c {
	env e;
	Test2.OuterStruct[] myArray;
	Test2.OuterStruct f1 = myArray[0];
	Test2.OuterStruct f2 = myArray[1];
	require(f1.anArray.length == f2.a);
	require(f1.structArray[f2.b].x == f2.anArray[f2.b]);
	assert decodeThing(e, myArray) == true;
}

rule test_c_inv {
	env e;
	Test2.OuterStruct[] myArray;
	require decodeThing(e, myArray) == true;
	Test2.OuterStruct f1 = myArray[0];
	Test2.OuterStruct f2 = myArray[1];
	assert f1.anArray.length == f2.a;
}

rule test_with_literal {
	env e;
	Test2.OuterStruct f1;
	Test2.OuterStruct f2;
	require(f1.anArray.length == f2.a);
	require(f1.structArray[f2.b].x == f2.anArray[f2.b]);
	Test2.OuterStruct[] myArray = [ f1, f2 ];
	assert decodeThing(e, myArray) == true;
}
